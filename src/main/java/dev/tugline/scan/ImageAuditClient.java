package dev.tugline.scan;

import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.http.FormatType;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tugline.config.TuglineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【模块】资源安全监测 · 图片 AI 审核
 * 【文件】ImageAuditClient.java（dev.tugline.scan）
 * 【核心功能】阿里云内容安全同步图片审核（porn/terrorism 场景）；
 *            未配置 AK 时优雅跳过（每进程仅提示一次，不阻断扫描主流程）
 * 【设计要点】- IAcsClient 惰性初始化（首次实际送审才创建）
 *            - 返回 Optional<Verdict>：empty = 未送审（未配置/调用失败），调用失败按跳过处理
 */
@Service
public class ImageAuditClient {

    private static final Logger log = LoggerFactory.getLogger(ImageAuditClient.class);

    /** 单图审核结论 */
    public record Verdict(String suggestion, String label) {
        public boolean blocked() {
            return "block".equalsIgnoreCase(suggestion);
        }
    }

    private final TuglineProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();
    private volatile IAcsClient client;

    public ImageAuditClient(TuglineProperties props) {
        this.props = props;
    }

    /** 是否已配置审核 AK */
    public boolean configured() {
        return notBlank(props.getScanImageAuditKey()) && notBlank(props.getScanImageAuditSecret());
    }

    /**
     * 同步审核单张图片。
     *
     * @return empty = 未送审（未配置 AK 或调用异常，记 warn 后跳过）
     */
    public Optional<Verdict> audit(byte[] imageBytes, String name) {
        if (!configured()) {
            warnOnce("image-not-configured",
                    "[ContentScan] 未配置图片审核 AK（tugline.scan.image-audit-key），跳过图片审核（不阻断）");
            return Optional.empty();
        }
        try {
            String region = props.getScanImageAuditRegion();
            CommonRequest request = new CommonRequest();
            request.setSysMethod(MethodType.POST);
            request.setSysDomain("green." + region + ".aliyuncs.com");
            request.setSysVersion("2018-05-09");
            request.setSysAction("ImageSyncScan");
            request.setSysUriPattern("/green/image/scan");
            request.setSysRegionId(region);
            request.setHttpContentType(FormatType.JSON);
            request.setSysAccept(FormatType.JSON);

            ObjectNode task = mapper.createObjectNode();
            task.put("dataId", UUID.randomUUID().toString());
            task.put("imageBase64", Base64.getEncoder().encodeToString(imageBytes));
            ArrayNode tasks = mapper.createArrayNode();
            tasks.add(task);

            ObjectNode body = mapper.createObjectNode();
            body.put("bizScenario", "tugline");
            body.set("tasks", tasks);
            request.setHttpContent(mapper.writeValueAsBytes(body), "UTF-8", FormatType.JSON);

            CommonResponse response = client().getCommonResponse(request);
            JsonNode root = mapper.readTree(response.getData());
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode results = data.get(0).path("results");
                if (results.isArray() && !results.isEmpty()) {
                    JsonNode r = results.get(0);
                    String suggestion = r.path("suggestion").asText("pass");
                    String label = r.path("label").asText("normal");
                    if (!"pass".equalsIgnoreCase(suggestion)) {
                        log.warn("[ContentScan] 图片审核命中建议={} label={} name={}", suggestion, label, name);
                    }
                    return Optional.of(new Verdict(suggestion, label));
                }
            }
            return Optional.of(new Verdict("pass", "normal"));
        } catch (Exception e) {
            warnOnce("image-call-failed",
                    "[ContentScan] 图片审核调用失败（跳过送审，不阻断）: " + e.toString());
            return Optional.empty();
        }
    }

    private IAcsClient client() {
        IAcsClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    DefaultProfile profile = DefaultProfile.getProfile(
                            props.getScanImageAuditRegion(),
                            props.getScanImageAuditKey(),
                            props.getScanImageAuditSecret());
                    client = new DefaultAcsClient(profile);
                }
                c = client;
            }
        }
        return c;
    }

    /** 每个 key 只提示一次，避免日志刷屏 */
    private void warnOnce(String key, String msg) {
        if (warned.putIfAbsent(key, true) == null) {
            log.warn(msg);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
