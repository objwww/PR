package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.auth.domain.AuthEvent;
import com.objwww.pr.control.auth.domain.AuthEventRepository;
import com.objwww.pr.control.auth.domain.AuthEventType;
import com.objwww.pr.control.infrastructure.auth.MachineBearerAuthnFilter;
import com.objwww.pr.control.infrastructure.auth.PlatformUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 认证最小闭环（EX-C3a）：Spring Security 6 双半边——
 * <ul>
 *   <li>浏览器：服务端会话（formLogin 处理器在 /api/auth/login，JSON 应答）+
 *       SPA 标准 CSRF（XSRF-TOKEN cookie → X-XSRF-TOKEN 头）；AUTH-1 双提供者：
 *       平台账号表（V44 platform_user，active=true 方可登录）为主登录面——
 *       登录权限独立于值班花名册，duty_member 纯排班语义；env 预置单 operator
 *       账号保留为引导管理员（例外，compose 强制 :? 传入，本地默认仅 dev）；</li>
 *   <li>机器：既有四条静态 bearer 契约原样保留，经
 *       {@link MachineBearerAuthnFilter} 按线铸角色（webhook/operator/release）。</li>
 * </ul>
 * 授权 deny-by-default：机器线与用户线按路径矩阵分权（operator vs release 即
 * 既有两 bearer 线别的升格）；SSE stream GET 凭短 TTL 单次换票（票即能力凭证，
 * FUT-34），换票 POST 仍走会话/bearer 认证。
 *
 * <p>审计：LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT 同步落 auth_event（V42），
 * 异常不吞——审计不可达时登录判负（B-28 律）。默认 profile（无 DataSource）
 * 审计仓储缺席时不落表：审计面随持久化装配面在场。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ACTOR_MACHINE_PREFIX = "machine:";

    private final AuthEventRepository authEvents;

    public SecurityConfig(ObjectProvider<AuthEventRepository> authEvents) {
        this.authEvents = authEvents.getIfAvailable();
    }

    // ------------------------------------------------------------------ 过滤链

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            MachineBearerAuthnFilter machineBearerAuthnFilter) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // CSRF 防的是浏览器自动携带的 cookie 凭证——机器 bearer 面
                        // （AM webhook / release 线脚本）攻击者无法跨站伪造头，豁免；
                        // operator 写面保留 CSRF（浏览器 axios 走 XSRF cookie→头）。
                        // POST /api/duty/notifications = 127 duty-adapter 回写面（M7-17）：
                        // 唯一授权主体是 DUTY_ADAPTER bearer（会话/其余线皆 403），
                        // cookie 凭证打不进该面，路径级豁免不产生跨站伪造窗口
                        .ignoringRequestMatchers("/webhooks/**",
                                "/api/config-bundles/**", "/api/canary/**")
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/duty/notifications", "POST")))
                .addFilterBefore(machineBearerAuthnFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/csrf")
                        .permitAll()
                        // UI-1：当前登录名——任何已认证身份（会话/机器线）可用
                        .requestMatchers("/api/auth/me").authenticated()
                        // AUTH-1：平台账号管理面归 operator（浏览器会话与 machine:operator-line 同权）
                        .requestMatchers("/api/auth/users", "/api/auth/users/**").hasRole("OPERATOR")
                        .requestMatchers("/actuator/health").permitAll()
                        // SSE 换票后的开流端点：EventSource 无法带头，票即能力凭证（30s 单次）
                        .requestMatchers(HttpMethod.GET, "/api/rca-runs/*/events/stream").permitAll()
                        .requestMatchers("/webhooks/**").hasRole("MACHINE_WEBHOOK")
                        .requestMatchers("/api/config-bundles/**", "/api/canary/**").hasRole("RELEASE")
                        // M7-17：127 duty-adapter 台账回写（source=GATUS 只增不改，
                        // 无 delivery 行——投递由 127 直发）；置于 /api/duty/** 通配之前
                        .requestMatchers(HttpMethod.POST, "/api/duty/notifications")
                        .hasRole("DUTY_ADAPTER")
                        // M7-15：值班面归 operator（浏览器会话与 machine:operator-line 同权；
                        // 195 窄反代只放行 snapshot/notifications 两条到 127 adapter）
                        // UI-1：只读查询投影面 /api/v1/** 同归 operator（全 GET 零写面）；
                        // UI-5/UI-6：/api/eval/** 与 /api/agent-ops/** 同为只读投影面，同权；
                        // 方案 §三.12：/api/metrics/** 白名单代理（B6）同为只读投影面，同权；
                        // DR-02：/api/drills/** 演练读写面同归 operator（写面状态机推进
                        // 在 DB 授权层仍零开口——control_app 只增作业与停止两列）
                        // EN-06：MCP 挂载管理面（register/disable/enable/deregister/status）
                        // EN-10：版本中心只读查询面（发布/激活仍在 RELEASE 机器线 /api/config-bundles/**）
                        .requestMatchers("/api/rca-runs/**", "/api/cases/**", "/api/duty/**",
                                "/api/v1/**", "/api/eval/**", "/api/agent-ops/**",
                                "/api/metrics/**", "/api/drills/**",
                                "/api/mcp-servers/**", "/api/release-assets/**")
                        .hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((req, res, authentication) -> {
                            recordAuthEvent(AuthEventType.LOGIN_SUCCESS,
                                    authentication.getName(), req);
                            writeJson(res, 200, "{\"ok\":true}");
                        })
                        .failureHandler((req, res, exception) -> {
                            recordAuthEvent(AuthEventType.LOGIN_FAILURE,
                                    actorOf(req.getParameter("username")), req);
                            writeJson(res, 401, "{\"error\":\"login_failed\"}");
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler(authEventLogoutHandler())
                        .logoutSuccessHandler((req, res, authentication) ->
                                writeJson(res, 200, "{\"ok\":true}"))
                        .invalidateHttpSession(true))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((req, res, exception) ->
                                writeJson(res, 401, "{\"error\":\"unauthorized\"}"))
                        .accessDeniedHandler((req, res, exception) ->
                                writeJson(res, 403, "{\"error\":\"forbidden\"}")))
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }

    // ------------------------------------------------------------------ 机器半边

    @Bean
    public MachineBearerAuthnFilter machineBearerAuthnFilter(
            @Value("${app.alert.webhook.bearer:}") String webhookBearer,
            @Value("${app.alert.control-router.bearer:}") String controlRouterBearer,
            @Value("${app.operator.api.bearer:}") String operatorBearer,
            @Value("${app.release.api.bearer:}") String releaseBearer,
            @Value("${app.duty.adapter.bearer:}") String dutyAdapterBearer) {
        return new MachineBearerAuthnFilter(List.of(
                new MachineBearerAuthnFilter.MachineLine(
                        webhookBearer, ACTOR_MACHINE_PREFIX + "alertmanager", "MACHINE_WEBHOOK"),
                // M5-16 防自噬：控制面合成告警以独立 bearer 打同一 webhook 端点
                new MachineBearerAuthnFilter.MachineLine(
                        controlRouterBearer, ACTOR_MACHINE_PREFIX + "control-router", "MACHINE_WEBHOOK"),
                new MachineBearerAuthnFilter.MachineLine(
                        operatorBearer, ACTOR_MACHINE_PREFIX + "operator-line", "OPERATOR"),
                new MachineBearerAuthnFilter.MachineLine(
                        releaseBearer, ACTOR_MACHINE_PREFIX + "release-line", "RELEASE"),
                // M7-17：127 duty-adapter 回写线（只铸 POST /api/duty/notifications 一面）
                new MachineBearerAuthnFilter.MachineLine(
                        dutyAdapterBearer, ACTOR_MACHINE_PREFIX + "duty-adapter", "DUTY_ADAPTER")));
    }

    // ------------------------------------------------------------------ 用户半边（单人运维，如实标注）

    /** 单 operator 账号（env 预置）；本地默认仅 dev——compose 以 :? 强制注入真值。 */
    @Bean
    public UserDetailsService authUserDetailsService(
            @Value("${app.auth.operator.username:operator}") String username,
            @Value("${app.auth.operator.password-bcrypt:$2a$10$XYv0PhQ60xu0aI8S/MlGSO//jvVykSfOAdXAXG6FwG4eHDFHoHwgO}")
            String passwordBcrypt) {
        UserDetails operator = User.withUsername(username)
                .password(passwordBcrypt)
                .roles("OPERATOR")
                .build();
        return new InMemoryUserDetailsManager(operator);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * AUTH-1 双提供者（显式 ProviderManager，bean 名 authenticationManager——
     * Spring Security 6 惯例：发布该名 bean 即被 formLogin 采用，官方
     * "publish an AuthenticationManager bean" 装配式）：
     * <ol>
     *   <li>env operator（InMemoryUserDetailsManager）——引导管理员例外，保留；</li>
     *   <li>平台账号 DB 面（{@link PlatformUserDetailsService}，docker profile
     *       由 PersistenceConfig 装配；默认 profile 无 DataSource 时缺席，仅剩引导
     *       管理员）。</li>
     * </ol>
     * 为何显式声明：两个 UserDetailsService 在场时全局自动装配无法定夺唯一来源
     * （InitializeUserDetailsBeanManagerConfigurer 遇歧义直接跳过），显式
     * ProviderManager 钉死两者同过 formLogin。提供者顺序无关——一提供者判负
     * （BadCredentials）即让位下一提供者，皆负则统一 401 login_failed。
     * 审计不受影响：LOGIN_SUCCESS/FAILURE 由 formLogin 处理器落 auth_event，
     * 与提供者无关。机器五条 bearer 线走 MachineBearerAuthnFilter 自铸
     * Authentication，不经本管理器。
     */
    @Bean
    public AuthenticationManager authenticationManager(
            @Qualifier("authUserDetailsService") UserDetailsService authUserDetailsService,
            ObjectProvider<PlatformUserDetailsService> platformUserDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider envOperator = new DaoAuthenticationProvider();
        envOperator.setUserDetailsService(authUserDetailsService);
        envOperator.setPasswordEncoder(passwordEncoder);
        List<AuthenticationProvider> providers = new ArrayList<>(List.of(envOperator));
        PlatformUserDetailsService platformUsers = platformUserDetailsService.getIfAvailable();
        if (platformUsers != null) {
            DaoAuthenticationProvider platformDb = new DaoAuthenticationProvider();
            platformDb.setUserDetailsService(platformUsers);
            platformDb.setPasswordEncoder(passwordEncoder);
            providers.add(platformDb);
        }
        return new ProviderManager(providers);
    }

    // ------------------------------------------------------------------ 审计面

    private LogoutHandler authEventLogoutHandler() {
        return (request, response, authentication) -> {
            if (authentication != null
                    && !authentication.getName().startsWith(ACTOR_MACHINE_PREFIX)) {
                recordAuthEvent(AuthEventType.LOGOUT, authentication.getName(), request);
            }
        };
    }

    private void recordAuthEvent(AuthEventType type, String actor, HttpServletRequest request) {
        if (authEvents == null) {
            return;
        }
        String actorName = actor == null || actor.isBlank() ? "unknown" : actor;
        authEvents.record(new AuthEvent(actorName, type, remoteAddr(request),
                null, Instant.now()));
    }

    private static String actorOf(String submittedUsername) {
        return submittedUsername == null || submittedUsername.isBlank() ? "unknown" : submittedUsername;
    }

    /** 单层代理面地址：X-Forwarded-For 首值优先，否则连接对端。 */
    private static String remoteAddr(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static void writeJson(HttpServletResponse response, int status, String body)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    /**
     * SPA 标准 CSRF 请求处理（Spring Security 官方文档形）：写请求经头提交时取
     * cookie 原始值，经参数提交时走 XOR 解码。
     */
    static final class SpaCsrfTokenRequestHandler
            extends CsrfTokenRequestAttributeHandler implements CsrfTokenRequestHandler {
        private final XorCsrfTokenRequestAttributeHandler delegate =
                new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           Supplier<CsrfToken> csrfToken) {
            this.delegate.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
                return csrfToken.getToken();
            }
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
