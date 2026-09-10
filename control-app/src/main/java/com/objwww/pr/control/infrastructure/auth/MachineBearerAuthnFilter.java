package com.objwww.pr.control.infrastructure.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * 机器凭证认证过滤器（EX-C3a：机器凭证与浏览器用户身份分离的机器半边）。
 *
 * <p>既有四条静态 bearer 线的契约原样保留（AM webhook / operator 线 / release 线），
 * 命中即以 {@code machine:<线>} 主体 + 对应角色铸 Authentication——路径授权交给
 * SecurityFilterChain 的 authorize 规则；未携带或不匹配则保持未认证，由入口点
 * 返回 401。常量时间比较（沿旧 authorized() 的时序侧信道防线）。
 */
public class MachineBearerAuthnFilter extends OncePerRequestFilter {

    /** 一条机器凭证线：token + 主体标识 + 授予角色（不含 ROLE_ 前缀）。 */
    public record MachineLine(String token, String principal, String role) {
        public MachineLine {
            Objects.requireNonNull(principal, "principal 不得为 null");
            Objects.requireNonNull(role, "role 不得为 null");
        }

        boolean usable() {
            return token != null && !token.isBlank();
        }

        boolean matches(String candidate) {
            return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                    token.trim().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final String PREFIX = "Bearer ";

    private final List<MachineLine> lines;

    public MachineBearerAuthnFilter(List<MachineLine> lines) {
        this.lines = List.copyOf(lines);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String candidate = authorization.substring(PREFIX.length()).trim();
            for (MachineLine line : lines) {
                if (line.usable() && line.matches(candidate)) {
                    SecurityContextHolder.getContext().setAuthentication(authenticationOf(line));
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private Authentication authenticationOf(MachineLine line) {
        return UsernamePasswordAuthenticationToken.authenticated(
                line.principal(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + line.role())));
    }
}
