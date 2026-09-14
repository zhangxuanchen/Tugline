package dev.tugline.security;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tugline.config.TuglineProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 控制台密码持久化（auth.json）。
 * - 「修改密码」后把 BCrypt 哈希落盘，重启后仍生效（优先级高于配置文件明文密码）
 * - 未修改过密码时文件不存在，登录走 tugline.auth.password 配置
 * - 原子写入：先写临时文件再 MOVE 替换
 */
@Component
public class AuthStore {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path file;

    /** 已自定义的密码哈希；null 表示尚未修改过（走配置文件密码） */
    private volatile String customHash;

    public AuthStore(TuglineProperties props) {
        this.file = Path.of(props.getDataDir(), "auth.json").toAbsolutePath().normalize();
        load();
    }

    private static class AuthState {
        public String passwordHash;
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (Files.exists(file)) {
                AuthState s = mapper.readValue(file.toFile(), AuthState.class);
                if (s != null && s.passwordHash != null && !s.passwordHash.isBlank()) {
                    customHash = s.passwordHash;
                }
            }
        } catch (Exception e) {
            System.err.println("[tugline] auth.json 加载失败，忽略自定义密码: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 当前生效的密码哈希；null 表示尚未自定义（调用方应回退到配置密码） */
    public String customPasswordHash() {
        return customHash;
    }

    public void savePasswordHash(String bcryptHash) {
        lock.writeLock().lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            AuthState s = new AuthState();
            s.passwordHash = bcryptHash;
            mapper.writeValue(tmp.toFile(), s);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            customHash = bcryptHash;
        } catch (IOException e) {
            throw new IllegalStateException("auth.json 保存失败: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
