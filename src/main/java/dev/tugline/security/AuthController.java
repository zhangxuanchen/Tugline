package dev.tugline.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 控制台登录接口
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties props;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthStore authStore;

    /** 当前生效的密码哈希（自定义 > 配置文件），修改密码后原地更新 */
    private volatile String passwordHash;

    public AuthController(AuthProperties props, JwtService jwtService,
                          PasswordEncoder passwordEncoder, AuthStore authStore) {
        this.props = props;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.authStore = authStore;
        // 启动时预计算 BCrypt 哈希，登录时直接比对：auth.json 里自定义的哈希优先
        String custom = authStore.customPasswordHash();
        this.passwordHash = custom != null ? custom : passwordEncoder.encode(props.getPassword());
    }

    /** 登录：校验用户名密码，签发 JWT */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        boolean ok = props.getUsername().equals(username)
                && passwordEncoder.matches(password, passwordHash);
        if (!ok) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        return ResponseEntity.ok(Map.of(
                "token", jwtService.generateToken(username),
                "username", username,
                "expiresIn", jwtService.getTtlSeconds()));
    }

    /** 当前登录用户（供前端校验 token 是否仍有效） */
    @GetMapping("/me")
    public Map<String, String> me(Authentication auth) {
        return Map.of("username", auth.getName());
    }

    /** 修改密码：校验当前密码后更新（BCrypt 落盘，重启不失效） */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPwd = body.getOrDefault("oldPassword", "");
        String newPwd = body.getOrDefault("newPassword", "");
        if (!passwordEncoder.matches(oldPwd, passwordHash)) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前密码不正确"));
        }
        if (newPwd == null || newPwd.trim().length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码至少 6 位"));
        }
        if (newPwd.equals(oldPwd)) {
            return ResponseEntity.badRequest().body(Map.of("error", "新密码不能与当前密码相同"));
        }
        String hash = passwordEncoder.encode(newPwd);
        authStore.savePasswordHash(hash);
        this.passwordHash = hash;
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
