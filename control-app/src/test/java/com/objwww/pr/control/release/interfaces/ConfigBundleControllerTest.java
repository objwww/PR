package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5-09 ConfigBundleController 边界 UT（standalone MockMvc + InMemory fake，
 * AlertWebhookControllerTest 同构）：发布幂等锚（同内容重发 replayed=true）、
 * 缺 content 400、激活/回滚/active 视图、CAS 竞争败者 409、未知 digest 404。
 * EX-C3a：401 面零落库上移 SecurityFilterChain（SecurityConfigTest 链级覆盖）；
 * actor=测试侧铸入 SecurityContext 的认证。
 *
 * <p>EN-02：activate/rollback 必带 expectedActiveRevision（缺 → 400，服务端不替
 * 用户推算预期——P06）；目标无未撤销 PASS 资格 → 422（QUALIFICATION_* 原因码，
 * S09/P07/P11）；RBAC=ROLE_RELEASE 在安全链（SecurityConfig，P05 不依赖按钮隐藏）。
 */
class ConfigBundleControllerTest {

    private InMemoryBundles repository;
    private InMemoryQualifications qualifications;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBundles();
        qualifications = new InMemoryQualifications();
        mvc = MockMvcBuilders.standaloneSetup(
                new ConfigBundleController(new ConfigBundleService(repository,
                        new NoopAssets(), qualifications))).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("release-operator", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** EN-01 资产仓储空实现（控制器 UT 不触资产面；闭包面在 ServiceTest/IT 覆盖） */
    static final class NoopAssets implements ReleaseAssetRepository {
        @Override
        public boolean insert(ReleaseAsset asset) {
            return true;
        }

        @Override
        public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            return Optional.empty();
        }
    }

    /** 测试内存认账面（与 ConfigBundleServiceTest 同构 + CAS 恒败开关供 409 面） */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        long revisionSeq = 0;
        Digest active;
        long activeRevision;
        Instant activatedAt;
        boolean alwaysLoseCas;

        @Override
        public long nextRevision() {
            return ++revisionSeq;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            if (findByDigest(bundle.bundleDigest()).isPresent()) {
                return false;
            }
            rows.add(bundle);
            return true;
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return rows.stream().filter(b -> b.bundleDigest().equals(digest)).findFirst();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.ofNullable(active);
        }

        @Override
        public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return active == null ? Optional.empty()
                    : Optional.of(new ConfigBundleRepository.ActivePointer(
                            active, activeRevision, activatedAt));
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at) {
            return activateQualified(toDigest, expectedActiveRevision, by, at, null);
        }

        @Override
        public boolean activateQualified(Digest toDigest, long expectedActiveRevision,
                String by, Instant at, ConfigBundleRepository.ActivationFact fact) {
            boolean expectationMet = active == null
                    ? expectedActiveRevision == 0
                    : !alwaysLoseCas && activeRevision == expectedActiveRevision;
            if (!expectationMet) {
                return false;
            }
            active = toDigest;
            activeRevision = findByDigest(toDigest).orElseThrow().revision();
            activatedAt = at;
            return true;
        }
    }

    /** 测试内存认账面（资格）：撤销 = 行替换 revoked 副本 */
    static final class InMemoryQualifications implements ReleaseQualificationRepository {
        final List<ReleaseQualification> rows = new ArrayList<>();

        @Override
        public boolean insert(ReleaseQualification qualification) {
            rows.add(qualification);
            return true;
        }

        @Override
        public Optional<ReleaseQualification> findUnrevokedFor(Digest candidate) {
            return rows.stream()
                    .filter(q -> q.candidateDigest().equals(candidate) && q.revokedAt() == null)
                    .reduce((first, second) -> second);
        }

        @Override
        public boolean revoke(UUID id, String by, String reason, Instant at) {
            for (int i = 0; i < rows.size(); i++) {
                ReleaseQualification row = rows.get(i);
                if (row.id().equals(id)) {
                    rows.set(i, row.revoked(by, reason, at));
                    return true;
                }
            }
            return false;
        }
    }

    private static String publishBody(String promptVersion) {
        return "{\"content\":{\"policy_version\":\"policy-2026-09\","
                + "\"prompt_version\":\"" + promptVersion + "\"}}";
    }

    private String publish(String promptVersion) throws Exception {
        String body = mvc.perform(post("/api/config-bundles")
                        .contentType("application/json")
                        .content(publishBody(promptVersion)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.bundleDigest");
    }

    /** EN-02：经服务面授予 PASS 资格（证明授予的 HTTP 面归 EN-09/EN-10） */
    private void grant(String digestHex) {
        service().grantQualification(new Digest(digestHex), null, "ef".repeat(32),
                "runner-v1", "grader-v1", "PASS", "MATCHED", "scope", "grader-1");
    }

    private ConfigBundleService service() {
        return new ConfigBundleService(repository, new NoopAssets(), qualifications);
    }

    /** 与发布体逐字对应的期望 digest（canonical 键序无关锚的独立复算） */
    private static String expectedDigest(String promptVersion) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", promptVersion);
        return Digest.sha256Of(
                com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                        .canonicalize(content)).hex();
    }

    // ---------------------------------------------------------------- 用例面

    // EX-C3a 迁出面：伪/缺 bearer → 401 全端点拒绝已上移 SecurityFilterChain
    //（SecurityConfigTest 链级红绿）；INV 面（未验签零落库）由安全链 401 先于 controller 保证。

    @Test
    @DisplayName("发布：200 带 64 位 digest/revision/replayed=false；同内容重发 replayed=true 同 digest")
    void publishRespondsDigestAndIdempotentReplay() throws Exception {
        mvc.perform(post("/api/config-bundles")
                        .contentType("application/json")
                        .content(publishBody("v7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleDigest").value(expectedDigest("v7")))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/config-bundles")
                        .contentType("application/json")
                        .content(publishBody("v7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleDigest").value(expectedDigest("v7")))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    @DisplayName("缺 content / content 非对象 → 400；密钥材料键 → 400（INV-AM5-5 出界面）")
    void malformedPublishBodyIs400() throws Exception {
        mvc.perform(post("/api/config-bundles")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/config-bundles")
                        .contentType("application/json").content("{\"content\":\"str\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/config-bundles")
                        .contentType("application/json")
                        .content("{\"content\":{\"policy_version\":\"p\",\"api_key\":\"k\"}}"))
                .andExpect(status().isBadRequest());
        assertThatRowsEmpty();
    }

    @Test
    @DisplayName("EN-02：激活必带 expectedActiveRevision（缺 → 400）；资格门拒绝 → 422 QUALIFICATION_ABSENT")
    void activateRequiresExpectedRevisionAndQualification() throws Exception {
        String d1 = publish("v7");

        mvc.perform(post("/api/config-bundles/" + d1 + "/activate")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/config-bundles/" + d1 + "/activate")
                        .contentType("application/json")
                        .content("{\"expectedActiveRevision\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("QUALIFICATION_ABSENT")));
        assertThatActivePointerNull();
    }

    @Test
    @DisplayName("激活：资格齐 200 revision 随行；未知 digest → 404；重复激活 = replayed=true")
    void activateFaces() throws Exception {
        String d1 = publish("v7");
        grant(d1);

        mvc.perform(post("/api/config-bundles/" + d1 + "/activate")
                        .contentType("application/json")
                        .content("{\"expectedActiveRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleDigest").value(d1))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/config-bundles/" + d1 + "/activate")
                        .contentType("application/json")
                        .content("{\"expectedActiveRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(post("/api/config-bundles/" + Digest.sha256Of("ghost").hex() + "/activate")
                        .contentType("application/json")
                        .content("{\"expectedActiveRevision\":0}"))
                .andExpect(status().isNotFound());
        assertThatActivePointer(d1);
    }

    @Test
    @DisplayName("回滚：pointer 指回旧 digest（目标同过资格门）；缺 toDigest → 400；active 视图跟随")
    void rollbackRewindsActivePointer() throws Exception {
        String d1 = publish("v7");
        String d2 = publish("v8");
        grant(d1);
        grant(d2);
        activate(d1, 0);
        activate(d2, 1);

        mvc.perform(post("/api/config-bundles/rollback")
                        .contentType("application/json")
                        .content("{\"toDigest\":\"" + d1 + "\",\"expectedActiveRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleDigest").value(d1))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/config-bundles/rollback")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThatActivePointer(d1);
    }

    @Test
    @DisplayName("active 视图：未激活 → 404 never_activated；激活后 → digest/revision/activatedAt")
    void activeViewFaces() throws Exception {
        mvc.perform(get("/api/config-bundles/active"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("never_activated"));

        String d1 = publish("v7");
        grant(d1);
        activate(d1, 0);
        mvc.perform(get("/api/config-bundles/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleDigest").value(d1))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.activatedAt").isString());
    }

    @Test
    @DisplayName("CAS 竞争败者 → 409 携当前指针（零状态改写）")
    void casLoserGets409WithCurrentPointer() throws Exception {
        String d1 = publish("v7");
        String d2 = publish("v8");
        grant(d1);
        grant(d2);
        activate(d1, 0);
        repository.alwaysLoseCas = true;

        mvc.perform(post("/api/config-bundles/" + d2 + "/activate")
                        .contentType("application/json")
                        .content("{\"expectedActiveRevision\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("activation_conflict"))
                .andExpect(jsonPath("$.activeDigest").value(d1));
        assertThatActivePointer(d1);
    }

    // ---------------------------------------------------------------- 内部

    private void activate(String digest, long expectedActiveRevision) throws Exception {
        mvc.perform(post("/api/config-bundles/" + digest + "/activate")
                        .contentType("application/json")
                        .content("{\"expectedActiveRevision\":" + expectedActiveRevision + "}"))
                .andExpect(status().isOk());
    }

    private void assertThatActivePointer(String expectedDigestHex) {
        org.assertj.core.api.Assertions.assertThat(repository.findActivePointer())
                .hasValueSatisfying(p ->
                        org.assertj.core.api.Assertions.assertThat(p.bundleDigest().hex())
                                .isEqualTo(expectedDigestHex));
    }

    private void assertThatActivePointerNull() {
        org.assertj.core.api.Assertions.assertThat(repository.activeDigest()).isEmpty();
    }

    private void assertThatRowsEmpty() {
        org.assertj.core.api.Assertions.assertThat(repository.rows).isEmpty();
    }
}
