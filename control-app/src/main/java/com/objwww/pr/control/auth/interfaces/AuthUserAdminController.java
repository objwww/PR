package com.objwww.pr.control.auth.interfaces;

import com.objwww.pr.control.auth.domain.PlatformUserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AUTH-1 平台账号管理面（/api/auth/users，ROLE_OPERATOR——SecurityConfig 矩阵）。
 * 写-only 密码纪律：创建/重置两路皆服务端 BCrypt 入库（≥8 位），
 * 读接口（列表）永不出 password_hash。
 *
 * <p>生效语义（v1）：停用即无法新登录、改密下次登录生效；已发会话不强制踢出。
 * 防自锁：停用当前登录者本人账号一律 400。
 */
@RestController
@Profile("docker")
@RequestMapping("/api/auth/users")
public class AuthUserAdminController {

    /** 密码策略下界（位） */
    static final int MIN_PASSWORD_LENGTH = 8;

    /** 角色列白名单形（防注入怪值；默认 OPERATOR） */
    private static final java.util.regex.Pattern ROLE_PATTERN =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    private final PlatformUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthUserAdminController(PlatformUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<PlatformUserRepository.UserView> list() {
        return users.listUsers();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String username = text(body.get("username"));
        String password = text(body.get("password"));
        if (username == null || username.isBlank()) {
            return badRequest("username 必填");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return badRequest("password 必填且至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        String role = text(body.get("role"));
        if (role == null || role.isBlank()) {
            role = "OPERATOR";
        } else {
            role = role.toUpperCase();
            if (!ROLE_PATTERN.matcher(role).matches()) {
                return badRequest("role 只接受大写字母/数字/下划线");
            }
        }
        try {
            PlatformUserRepository.UserView created = users.insertUser(username,
                    text(body.get("displayName")), passwordEncoder.encode(password), role);
            return ResponseEntity.ok(Map.of("ok", true, "user", created));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409)
                    .body(Map.of("ok", false, "error", "username 已存在"));
        }
    }

    /** 重置密码（改密下次登录生效；v1 不踢已发会话） */
    @PostMapping("/{username}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable String username, @RequestBody Map<String, Object> body) {
        String password = text(body.get("password"));
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return badRequest("password 必填且至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (!users.updatePassword(username, passwordEncoder.encode(password))) {
            return notFound();
        }
        return ok();
    }

    /** 启用/停用（停用即无法新登录；停用本人账号拒绝——防自锁） */
    @PostMapping("/{username}/active")
    public ResponseEntity<Map<String, Object>> setActive(
            Authentication authentication,
            @PathVariable String username, @RequestBody Map<String, Object> body) {
        Boolean active = body.get("active") instanceof Boolean b ? b : null;
        if (active == null) {
            return badRequest("active 必填（true/false）");
        }
        if (!active && authentication != null && authentication.getName().equals(username)) {
            return badRequest("不能停用当前登录者本人账号（防自锁）");
        }
        if (!users.updateActive(username, active)) {
            return notFound();
        }
        return ok();
    }

    // ---------------- 内部

    private static ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not_found"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
