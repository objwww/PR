package com.objwww.pr.control.auth.interfaces;

import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SPA 认证引导（EX-C3a）：GET /api/auth/csrf 强制实例化 deferred CsrfToken，
 * CookieCsrfTokenRepository 随响应落 XSRF-TOKEN cookie，token 值同时入响应体
 * （Spring Security 官方 SPA 形：cookie 供 axios 自动回带头，body 供非 cookie 面）。
 * 无持久化依赖，全 profile 在场。
 */
@RestController
public class AuthController {

    @GetMapping("/api/auth/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    /** 当前登录名（UI-1：任何已认证身份可用——浏览器会话用户名或机器线主体） */
    @GetMapping("/api/auth/me")
    public Map<String, Object> me() {
        return Map.of("name", AuthenticatedActor.name());
    }
}
