package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
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
 */
class ConfigBundleControllerTest {


    private InMemoryBundles repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBundles();
        mvc = MockMvcBuilders.standaloneSetup(
                new ConfigBundleController(new ConfigBundleService(repository))).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("release-operator", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 测试内存认账面（与 ConfigBundleServiceTest 同构 + CAS 恒败开关供 409 面） */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        long revisionSeq = 0;
        Digest active;
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
                            active, findByDigest(active).orElseThrow().revision(), activatedAt));
        }

        @Override
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at) {
            if (alwaysLoseCas
                    || (active == null && expectedCurrent != null)
                    || (active != null && !active.equals(expectedCurrent))) {
                return false;
            }
            active = toDigest;
            activatedAt = at;
            return true;
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
    @DisplayName("激活：200 revision 随行；未知 digest → 404；重复激活 = replayed=true")
    void activateFaces() throws Exception {
        String d1 = publish("v7");

        mvc.perform(post("/api/config-bundles/" + d1 + "/activate")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleDigest").value(d1))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/config-bundles/" + d1 + "/activate")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(post("/api/config-bundles/" + Digest.sha256Of("ghost").hex() + "/activate")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertThatActivePointer(d1);
    }

    @Test
    @DisplayName("回滚：pointer 指回旧 digest；缺 toDigest → 400；active 视图跟随")
    void rollbackRewindsActivePointer() throws Exception {
        String d1 = publish("v7");
        String d2 = publish("v8");
        activate(d1);
        activate(d2);

        mvc.perform(post("/api/config-bundles/rollback")
                        .contentType("application/json")
                        .content("{\"toDigest\":\"" + d1 + "\"}"))
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
        activate(d1);
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
        activate(d1);
        repository.alwaysLoseCas = true;

        mvc.perform(post("/api/config-bundles/" + d2 + "/activate")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("activation_conflict"))
                .andExpect(jsonPath("$.activeDigest").value(d1));
        assertThatActivePointer(d1);
    }

    // ---------------------------------------------------------------- 内部

    private void activate(String digest) throws Exception {
        mvc.perform(post("/api/config-bundles/" + digest + "/activate")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    private void assertThatActivePointer(String expectedDigestHex) {
        org.assertj.core.api.Assertions.assertThat(repository.findActivePointer())
                .hasValueSatisfying(p ->
                        org.assertj.core.api.Assertions.assertThat(p.bundleDigest().hex())
                                .isEqualTo(expectedDigestHex));
    }

    private void assertThatRowsEmpty() {
        org.assertj.core.api.Assertions.assertThat(repository.rows).isEmpty();
    }
}
