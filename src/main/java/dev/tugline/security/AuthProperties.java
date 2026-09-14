package dev.tugline.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制台登录鉴权配置（对应 application.yml 的 tugline.auth.*）
 * 生产环境建议通过环境变量覆盖：TUGLINE_AUTH_USERNAME / TUGLINE_AUTH_PASSWORD / TUGLINE_JWT_SECRET
 */
@ConfigurationProperties(prefix = "tugline.auth")
public class AuthProperties {

    /** 登录用户名 */
    private String username = "admin";

    /** 登录密码（明文，登录时用 BCrypt 比对；公网部署务必用环境变量覆盖） */
    private String password = "admin123";

    /** JWT HS256 签名密钥（至少 32 字符） */
    private String jwtSecret = "tugline-default-jwt-secret-change-me-in-production-0123456789";

    /** token 有效期（小时） */
    private int ttlHours = 72;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
}
