package dev.tugline.security;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录图形验证码：随机 4 位字符手绘成 SVG，一次性消费，3 分钟过期。
 * 去掉易混淆字符（0/O、1/I/l），避免人工看错导致登录受挫。
 */
@Service
public class CaptchaService {

    private static final Duration TTL = Duration.ofMinutes(3);
    /** 验证码池上限，超过即清理过期项，防止恶意刷接口撑爆内存 */
    private static final int MAX_STORE = 200;
    private static final String CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String[] PALETTE = {"2f6f5e", "b4632f", "3f5aa6", "8a3fb0", "a63f4f"};

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 验证码载荷：id 用于提交时回传，svg 直接渲染成 <img> */
    public record Captcha(String captchaId, String svg) {}

    private record Entry(String answer, long expiresAt) {}

    /** 生成新验证码并入库 */
    public Captcha generate() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new Entry(code.toString(), System.currentTimeMillis() + TTL.toMillis()));
        if (store.size() > MAX_STORE) {
            long now = System.currentTimeMillis();
            store.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        }
        return new Captcha(id, render(code.toString()));
    }

    /** 校验并消费（一次性），不匹配或过期均返回 false */
    public boolean verify(String captchaId, String answer) {
        if (captchaId == null || answer == null) {
            return false;
        }
        Entry entry = store.remove(captchaId);
        return entry != null
                && System.currentTimeMillis() < entry.expiresAt()
                && entry.answer().equalsIgnoreCase(answer.trim());
    }

    /** 手绘 SVG：字符随机位置/旋转/配色，叠加噪声曲线 */
    private String render(String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='110' height='38' viewBox='0 0 110 38'>")
          .append("<rect width='110' height='38' fill='#f2f4f3'/>");
        for (int i = 0; i < 3; i++) {
            sb.append("<path d='M0 ").append(6 + random.nextInt(26))
              .append(" Q 30 ").append(random.nextInt(38)).append(" 55 ").append(random.nextInt(38))
              .append(" T 110 ").append(random.nextInt(38))
              .append("' stroke='#").append(PALETTE[random.nextInt(PALETTE.length)])
              .append("' stroke-width='1' fill='none' opacity='.45'/>");
        }
        for (int i = 0; i < code.length(); i++) {
            int x = 14 + i * 24 + random.nextInt(7) - 3;
            int y = 26 + random.nextInt(7) - 3;
            int rot = random.nextInt(41) - 20;
            sb.append("<text x='").append(x).append("' y='").append(y)
              .append("' font-size='24' font-weight='bold' font-family='Georgia,serif' fill='#")
              .append(PALETTE[random.nextInt(PALETTE.length)])
              .append("' transform='rotate(").append(rot).append(' ').append(x).append(' ').append(y)
              .append(")'>").append(code.charAt(i)).append("</text>");
        }
        return sb.append("</svg>").toString();
    }
}
