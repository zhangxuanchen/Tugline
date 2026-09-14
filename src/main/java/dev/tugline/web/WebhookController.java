package dev.tugline.web;

import dev.tugline.model.Repo;
import dev.tugline.model.Run;
import dev.tugline.store.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * GitHub Webhook 入口。
 * - push 事件 + ref 过滤 + autoDeploy 判断 → 触发流水线
 * - 配置 secret 时强校验 X-Hub-Signature-256（HMAC-SHA256 恒时比较）
 * - ping 事件直接 200（GitHub 首次握手）
 */
@RestController
public class WebhookController {

    private final JsonStore store;
    private final dev.tugline.core.PipelineService pipeline;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookController(JsonStore store, dev.tugline.core.PipelineService pipeline) {
        this.store = store;
        this.pipeline = pipeline;
    }

    @PostMapping("/api/webhook/{repoId}")
    public ResponseEntity<?> handle(@PathVariable String repoId,
                                    @org.springframework.web.bind.annotation.RequestHeader(
                                            value = "X-GitHub-Event", required = false) String event,
                                    @org.springframework.web.bind.annotation.RequestHeader(
                                            value = "X-Hub-Signature-256", required = false) String signature,
                                    @org.springframework.web.bind.annotation.RequestBody byte[] body) {
        Repo repo = store.getRepo(repoId)
                .orElseThrow(() -> new NoSuchElementException("webhook 对应仓库不存在: " + repoId));

        // 签名校验
        if (repo.getSecret() != null && !repo.getSecret().isBlank()) {
            if (signature == null || !verify(secretBytes(repo.getSecret()), body, signature)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "签名校验失败"));
            }
        }

        if (event == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 X-GitHub-Event 头"));
        }
        if ("ping".equals(event)) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "pong"));
        }
        if (!"push".equals(event)) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "忽略事件 " + event));
        }

        try {
            JsonNode payload = mapper.readTree(body);
            String ref = payload.path("ref").asText("");
            String expectedRef = "refs/heads/" + repo.getBranch();
            if (!expectedRef.equals(ref)) {
                return ResponseEntity.ok(Map.of("ok", true,
                        "message", "忽略非目标分支推送: " + ref));
            }
            if (!repo.isAutoDeploy()) {
                return ResponseEntity.ok(Map.of("ok", true,
                        "message", "autoDeploy 已关闭，仅记录事件"));
            }
            Run run = pipeline.deploy(repo, Run.Trigger.WEBHOOK);
            if (run == null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "该仓库已有流水线在执行中"));
            }
            return ResponseEntity.accepted()
                    .body(Map.of("ok", true, "runId", run.getId(), "commit", run.getCommit()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "payload 解析失败"));
        }
    }

    private static byte[] secretBytes(String secret) {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC-SHA256 恒时校验，签名形如 "sha256=<hex>" */
    static boolean verify(byte[] secret, byte[] payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(payload);
            StringBuilder sb = new StringBuilder("sha256=");
            for (byte b : expected) {
                sb.append(String.format("%02x", b));
            }
            return MessageDigest.isEqual(
                    sb.toString().getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
