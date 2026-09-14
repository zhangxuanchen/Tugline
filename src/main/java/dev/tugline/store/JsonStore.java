package dev.tugline.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.tugline.model.Repo;
import dev.tugline.model.Run;
import dev.tugline.model.RuntimeState;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JSON 文件持久化（state.json）。
 * - 原子写入：先写临时文件再 MOVE 替换
 * - 读写锁保护并发
 * - 运行记录环形保留最近 50 条
 */
@Component
public class JsonStore {

    private static final int MAX_RUNS = 50;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path file;

    private List<Repo> repos = new ArrayList<>();
    private List<Run> runs = new ArrayList<>();
    private Map<String, RuntimeState> runtime = new LinkedHashMap<>();

    public JsonStore(dev.tugline.config.TuglineProperties props) {
        this.file = Path.of(props.getDataDir(), "state.json").toAbsolutePath().normalize();
        load();
    }

    // ---------- 内部状态 ----------

    private static class State {
        public List<Repo> repos = new ArrayList<>();
        public List<Run> runs = new ArrayList<>();
        public Map<String, RuntimeState> runtime = new LinkedHashMap<>();
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (Files.exists(file)) {
                State s = mapper.readValue(file.toFile(), State.class);
                if (s != null) {
                    repos = s.repos != null ? s.repos : new ArrayList<>();
                    runs = s.runs != null ? s.runs : new ArrayList<>();
                    runtime = s.runtime != null ? s.runtime : new LinkedHashMap<>();
                }
            }
        } catch (Exception e) {
            System.err.println("[tugline] state.json 加载失败，按空状态启动: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            State s = new State();
            s.repos = repos;
            s.runs = runs;
            s.runtime = runtime;
            mapper.writeValue(tmp.toFile(), s);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("state.json 保存失败: " + e.getMessage(), e);
        }
    }

    // ---------- Repo ----------

    public List<Repo> listRepos() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(repos);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Repo> getRepo(String id) {
        lock.readLock().lock();
        try {
            return repos.stream().filter(r -> r.getId().equals(id)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addRepo(Repo repo) {
        lock.writeLock().lock();
        try {
            repos.add(repo);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateRepo(Repo repo) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < repos.size(); i++) {
                if (repos.get(i).getId().equals(repo.getId())) {
                    repos.set(i, repo);
                    break;
                }
            }
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeRepo(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = repos.removeIf(r -> r.getId().equals(id));
            runtime.remove(id);
            runs.removeIf(r -> r.getRepoId().equals(id));
            if (removed) save();
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 所有已占用端口 */
    public List<Integer> usedPorts() {
        lock.readLock().lock();
        try {
            return repos.stream().map(Repo::getPort).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------- Run ----------

    public void addRun(Run run) {
        lock.writeLock().lock();
        try {
            runs.add(run);
            runs.sort(Comparator.comparingLong(Run::getCreatedAt).reversed());
            if (runs.size() > MAX_RUNS) {
                runs = new ArrayList<>(runs.subList(0, MAX_RUNS));
            }
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateRun(Run run) {
        lock.writeLock().lock();
        try {
            runs.removeIf(r -> r.getId().equals(run.getId()));
            runs.add(run);
            runs.sort(Comparator.comparingLong(Run::getCreatedAt).reversed());
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<Run> getRun(String id) {
        lock.readLock().lock();
        try {
            return runs.stream().filter(r -> r.getId().equals(id)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Run> listRuns(String repoId, int limit) {
        lock.readLock().lock();
        try {
            return runs.stream()
                    .filter(r -> r.getRepoId().equals(repoId))
                    .limit(limit)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 部署历史分页查询（时间倒序，页码从 1 开始） */
    public Map<String, Object> pageRuns(String repoId, int page, int size) {
        lock.readLock().lock();
        try {
            List<Run> filtered = runs.stream()
                    .filter(r -> r.getRepoId().equals(repoId))
                    .toList();
            int total = filtered.size();
            int pages = (total + size - 1) / size;
            int p = Math.min(Math.max(page, 1), Math.max(pages, 1));
            List<Run> items = filtered.stream()
                    .skip((long) (p - 1) * size)
                    .limit(size)
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", items);
            result.put("total", total);
            result.put("page", p);
            result.put("size", size);
            result.put("pages", pages);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Run> lastRunOf(String repoId) {
        lock.readLock().lock();
        try {
            return runs.stream().filter(r -> r.getRepoId().equals(repoId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------- Runtime ----------

    public RuntimeState runtimeOf(String repoId) {
        lock.readLock().lock();
        try {
            return runtime.get(repoId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setRuntime(String repoId, RuntimeState state) {
        lock.writeLock().lock();
        try {
            runtime.put(repoId, state);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeRuntime(String repoId) {
        lock.writeLock().lock();
        try {
            runtime.remove(repoId);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 服务重启后的进程状态恢复：清理已死亡进程的记录 */
    public void sweepDeadRuntime() {
        lock.writeLock().lock();
        try {
            boolean changed = runtime.entrySet().removeIf(e -> {
                long pid = e.getValue().getPid();
                return pid <= 0 || ProcessHandle.of(pid).isEmpty();
            });
            if (changed) save();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
