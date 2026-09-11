package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.auth.domain.AuthEvent;
import com.objwww.pr.control.auth.domain.AuthEventRepository;
import com.objwww.pr.control.auth.domain.AuthEventType;
import com.objwww.pr.control.auth.domain.PlatformUserRepository;
import com.objwww.pr.control.infrastructure.auth.PlatformUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EX-C3a 链级验收（SecurityFilterChain 全量面；六控制器 401 测试迁出的统一锚，
 * 各测试文件「上移 SecurityFilterChain」注释指向此处）：
 * <ul>
 *   <li>deny-by-default 矩阵：未认证 401 JSON；机器四线按路径分权（operator 线
 *       不可触 release/canary 面，release 线不可触 operator 面，伪线恒 401）；</li>
 *   <li>机器面 CSRF 豁免（/webhooks/**、/api/config-bundles/**、/api/canary/**——
 *       防的是浏览器 cookie 自动携带，bearer 头无此面）；</li>
 *   <li>SSE 开流 GET 凭票 permitAll（FUT-34 票即能力凭证）；/actuator/health
 *       permitAll；其余 anyRequest denyAll；</li>
 *   <li>浏览器半边：CSRF 引导（XSRF-TOKEN cookie）→ formLogin JSON → 会话授权
 *       → logout 失效；登录成功/失败/登出审计一行一事（RecordingAuthEvents 假仓，
 *       真 PG 面由 195 部署段 drill 验证）。</li>
 * </ul>
 * 默认 profile：docker-only 控制器/PG 仓储不在场——「链已放行」以 404（无处理器）
 * 呈现，401/403 只出自安全链自身。
 */
@SpringBootTest(properties = {
        "app.alert.webhook.bearer=wh-line-token",
        "app.alert.control-router.bearer=cr-line-token",
        "app.operator.api.bearer=op-line-token",
        "app.release.api.bearer=rel-line-token",
        "app.duty.adapter.bearer=da-line-token"})
@AutoConfigureMockMvc
class SecurityConfigTest {

    private static final String XSRF = "X-XSRF-TOKEN";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthEventRepository authEvents;

    @BeforeEach
    void resetAudit() {
        audit().rows.clear();
    }

    private RecordingAuthEvents audit() {
        return (RecordingAuthEvents) authEvents;
    }

    /** 审计假仓：记录 AuthEvent 行供断言（SecurityConfig 经 ObjectProvider 取用） */
    static final class RecordingAuthEvents implements AuthEventRepository {
        final List<AuthEvent> rows = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuthEvent event) {
            rows.add(event);
        }
    }

    @TestConfiguration
    static class AuditFakeConfig {
        @Bean
        AuthEventRepository authEventRepository() {
            return new RecordingAuthEvents();
        }

        /**
         * AUTH-1 DB 面假件（默认 profile 无 PG）：alice 在职（OPERATOR）、
         * bob 停用、其余名字查无此人。
         */
        @Bean
        PlatformUserDetailsService platformUserDetailsService() {
            String aliceHash = new BCryptPasswordEncoder().encode("alice-pass-88");
            Map<String, PlatformUserRepository.AuthUser> users = Map.of(
                    "alice", new PlatformUserRepository.AuthUser("alice", aliceHash,
                            "OPERATOR", true),
                    "bob", new PlatformUserRepository.AuthUser("bob", aliceHash,
                            "OPERATOR", false));
            return new PlatformUserDetailsService(new PlatformUserRepository() {
                @Override
                public List<PlatformUserRepository.UserView> listUsers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public PlatformUserRepository.UserView insertUser(String u, String d, String h,
                                                                  String r) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean updatePassword(String u, String h) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean updateActive(String u, boolean a) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<PlatformUserRepository.AuthUser> findForAuth(String username) {
                    return Optional.ofNullable(users.get(username));
                }
            });
        }
    }

    // -------------------------------------------------------------- deny-by-default

    @Test
    @DisplayName("未认证访问业务面 → 401 {\"error\":\"unauthorized\"}")
    void unauthenticatedOperatorApiIs401Json() throws Exception {
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    @DisplayName("health permitAll（未认证 200）")
    void healthIsPermitAll() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("anyRequest denyAll：/actuator/metrics 未认证 401、已认证（operator 线）403")
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    // -------------------------------------------------------------- 机器四线矩阵

    @Test
    @DisplayName("operator 线：/api/rca-runs/** 放行（404 无处理器）；release/canary 面 403")
    void operatorLineRoleMatrix() throws Exception {
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID())
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/config-bundles/active")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/api/canary/status")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isForbidden());
    }

    /** EN-10 版本中心：资产/版本列表查询面进 OPERATOR 浏览器矩阵（只读；发布/激活
     * 仍在 RELEASE 机器线），未认证 401、deny-by-default 不变 */
    @Test
    @DisplayName("EN-10：operator 线 /api/release-assets/** 放行（404 无处理器）；未认证 401")
    void releaseAssetsFaceOperatorMatrix() throws Exception {
        mvc.perform(get("/api/release-assets")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/release-assets/bundles")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/release-assets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("release 线：/api/config-bundles/** POST 放行且 CSRF 豁免；operator 面 403")
    void releaseLineRoleMatrix() throws Exception {
        mvc.perform(post("/api/config-bundles")
                        .header("Authorization", "Bearer rel-line-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID())
                        .header("Authorization", "Bearer rel-line-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("duty 面（M7-15）：operator 线 GET 放行（404 无处理器）；release 线/未认证拒绝；"
            + "写面不在 CSRF 豁免清单——bearer 无 CSRF 头 403（写面主人=浏览器会话+CSRF）")
    void dutyFaceRoleMatrix() throws Exception {
        mvc.perform(get("/api/duty/notifications")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/duty/notifications")
                        .header("Authorization", "Bearer rel-line-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/api/duty/notifications"))
                .andExpect(status().isUnauthorized());
        // EX-D1 群模拟 feed 并入同一认证面：operator 线放行（404 无处理器）；未认证 401
        mvc.perform(get("/api/duty/notifications/feed")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/duty/notifications/feed"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/duty/notifications/" + UUID.randomUUID() + "/read")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    @DisplayName("M7-17 回写面：duty-adapter 线 POST /api/duty/notifications 放行且 CSRF 豁免"
            + "（唯一授权主体=bearer，cookie 凭证打不进）；operator 线 POST 同路径 403；"
            + "adapter 线 GET 同路径仍 403（只铸写面）")
    void dutyAdapterWriteBackMatrix() throws Exception {
        mvc.perform(post("/api/duty/notifications")
                        .header("Authorization", "Bearer da-line-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/duty/notifications")
                        .header("Authorization", "Bearer op-line-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(post("/api/duty/notifications")
                        .header("Authorization", "Bearer forged-da-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());   // 伪线不铸 Authentication（过滤器语义）
        mvc.perform(get("/api/duty/notifications")
                        .header("Authorization", "Bearer da-line-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("webhook 线：wh/cr 两条 bearer 皆放行 /webhooks/**（M5-16 双线保留）且 CSRF 豁免；伪线 401")
    void webhookLineMatrix() throws Exception {
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer wh-line-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer cr-line-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/webhooks/alertmanager")
                        .header("Authorization", "Bearer forged-line-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(post("/webhooks/alertmanager")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SSE 开流 GET 凭票 permitAll（FUT-34）：无认证到达 dispatcher（404）")
    void sseStreamGetIsPermitAll() throws Exception {
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID() + "/events/stream"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------- UI-1 只读投影面

    @Test
    @DisplayName("UI-1（/api/v1/**）：operator 线放行（docker 控制器不在场 → 404）；"
            + "release 线 403；未认证 401")
    void uiV1FaceRoleMatrix() throws Exception {
        mvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/incidents")
                        .header("Authorization", "Bearer rel-line-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/api/auth/me（UI-1）：任何已认证主体 200 {\"name\": 主体}；未认证 401")
    void authMeReturnsAuthenticatedName() throws Exception {
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("machine:operator-line"));
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer rel-line-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("machine:release-line"));
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // -------------------------------------------------------------- 浏览器半边

    /** CSRF 引导：cookie XSRF-TOKEN + body token 同值（SPA 双面取用） */
    private String bootstrapCsrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value(XSRF))
                .andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.token");
    }

    @Test
    @DisplayName("登录→会话授权→登出→会话失效；审计 LOGIN_SUCCESS/LOGOUT 各一行")
    void loginSessionLogoutFlow() throws Exception {
        String token = bootstrapCsrf();

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .header(XSRF, token)
                        .param("username", "operator").param("password", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(audit().rows).hasSize(1);
        assertThat(audit().rows.get(0).eventType()).isEqualTo(AuthEventType.LOGIN_SUCCESS);
        assertThat(audit().rows.get(0).actor()).isEqualTo("operator");

        // 会话授权：operator 面到达 dispatcher（404），release 面仍 403（角色边界）
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/config-bundles/active").session(session))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/logout")
                        .header(XSRF, token).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(audit().rows).hasSize(2);
        assertThat(audit().rows.get(1).eventType()).isEqualTo(AuthEventType.LOGOUT);

        // 会话已失效：同一 JSESSIONID 再访 → 401
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID()).session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("错误口令 → 401 {\"error\":\"login_failed\"}；审计 LOGIN_FAILURE actor=提交账号")
    void wrongPasswordIs401AndAudited() throws Exception {
        String token = bootstrapCsrf();

        mvc.perform(post("/api/auth/login")
                        .header(XSRF, token)
                        .param("username", "operator").param("password", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("login_failed"));

        assertThat(audit().rows).hasSize(1);
        assertThat(audit().rows.get(0).eventType()).isEqualTo(AuthEventType.LOGIN_FAILURE);
        assertThat(audit().rows.get(0).actor()).isEqualTo("operator");
    }

    @Test
    @DisplayName("无 CSRF 头的浏览器写请求 → 403（会话 cookie 自动携带正是 CSRF 所防）")
    void browserPostWithoutCsrfTokenIs403() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .param("username", "operator").param("password", "operator"))
                .andExpect(status().isForbidden());
        assertThat(audit().rows).isEmpty();
    }

    // -------------------------------------------------------------- AUTH-1 平台账号登录

    @Test
    @DisplayName("AUTH-1：在职平台账号可登录（200 + 会话放行 operator 面）；审计 actor=登录名")
    void platformUserLoginSucceeds() throws Exception {
        String token = bootstrapCsrf();

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .header(XSRF, token)
                        .param("username", "alice").param("password", "alice-pass-88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(audit().rows).hasSize(1);
        assertThat(audit().rows.get(0).eventType()).isEqualTo(AuthEventType.LOGIN_SUCCESS);
        assertThat(audit().rows.get(0).actor()).isEqualTo("alice");

        // ROLE_OPERATOR 铸权：值班面到达 dispatcher（404 无处理器），release 面 403
        mvc.perform(get("/api/duty/notifications").session(session))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/config-bundles/active").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AUTH-1：非账号 / 停用账号一律 401 login_failed"
            + "（统一 BadCredentials，不区分原因），各落 LOGIN_FAILURE 一行")
    void platformUserLoginRejectsUnknownAndInactive() throws Exception {
        String token = bootstrapCsrf();

        mvc.perform(post("/api/auth/login").header(XSRF, token)
                        .param("username", "ghost").param("password", "alice-pass-88"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("login_failed"));
        mvc.perform(post("/api/auth/login").header(XSRF, token)
                        .param("username", "bob").param("password", "alice-pass-88"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("login_failed"));

        assertThat(audit().rows).hasSize(2);
        assertThat(audit().rows).allMatch(e -> e.eventType() == AuthEventType.LOGIN_FAILURE);
        assertThat(audit().rows.stream().map(AuthEvent::actor))
                .containsExactly("ghost", "bob");
    }

    @Test
    @DisplayName("AUTH-1 账号管理面（/api/auth/users/**）：operator 线放行（docker 控制器"
            + "不在场 → 404）；release 线 403；未认证 401")
    void authUsersFaceRoleMatrix() throws Exception {
        mvc.perform(get("/api/auth/users")
                        .header("Authorization", "Bearer op-line-token"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/users")
                        .header("Authorization", "Bearer rel-line-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/api/auth/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }
}
