package dev.tugline.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破：同一 IP 在 10 分钟窗口内密码错 5 次，锁定 15 分钟。
 * 只统计密码失败（验证码失败不计入），避免攻击者用垃圾验证码恶意锁死真实用户。
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration LOCK = Duration.ofMinutes(15);
    /** 记录数上限，超过即清理过期项，防止伪造 IP 刷爆内存 */
    private static final int MAX_STORE = 1000;

    private final ConcurrentHashMap<String, Fail> fails = new ConcurrentHashMap<>();

    private record Fail(int count, long windowStart, long lockedUntil) {}

    /** 被锁定则返回剩余分钟数，否则返回 0 */
    public int lockedMinutes(String ip) {
        Fail f = fails.get(ip);
        if (f == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (f.lockedUntil() > now) {
            return (int) Math.ceil((f.lockedUntil() - now) / 60000.0);
        }
        if (now - f.windowStart() > WINDOW.toMillis()) {
            fails.remove(ip);
        }
        return 0;
    }

    /** 登录成功，清空该 IP 计数 */
    public void onSuccess(String ip) {
        fails.remove(ip);
    }

    /** 密码失败计数，达到阈值触发锁定 */
    public void onFailure(String ip) {
        long now = System.currentTimeMillis();
        fails.compute(ip, (k, f) -> {
            if (f == null || now - f.windowStart() > WINDOW.toMillis()) {
                return new Fail(1, now, 0);
            }
            int count = f.count() + 1;
            return new Fail(count, f.windowStart(),
                    count >= MAX_FAILS ? now + LOCK.toMillis() : 0);
        });
        if (fails.size() > MAX_STORE) {
            fails.entrySet().removeIf(e -> {
                Fail f = e.getValue();
                return f.lockedUntil() < now && now - f.windowStart() > WINDOW.toMillis();
            });
        }
    }
}
