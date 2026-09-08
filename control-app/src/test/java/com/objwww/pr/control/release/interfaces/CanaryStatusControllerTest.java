package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.interfaces.CanaryStatusController.Capability;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-01 CanaryStatusController UT（C-64 只读观察面；standalone MockMvc + fakes，
 * ConfigBundleControllerTest 同构）：401 面零读库、就绪/缺件/capability digest
 * 投影、active bundle canary 段投影、NATIVE 决策计数、窗判定序列查询面。
 */
class CanaryStatusControllerTest {

    private static final String BEARER = "test-release-token";
    private static final String AUTH = "Authorization";

    private InMemoryBundles bundles;
    private CountingDecisions decisions;
    private InMemoryWindows windows;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        bundles = new InMemoryBundles();
        decisions = new CountingDecisions();
        windows = new InMemoryWindows();
    }

    private void readyMvc() {
        mvc = MockMvcBuilders.standaloneSetup(new CanaryStatusController(bundles,
                decisions, windows,
                new Capability(true, List.of(), "c".repeat(64)), BEARER)).build();
    }

    @Test
    @DisplayName("无 bearer → 401（零仓储触达）")
    void unauthorizedIs401() throws Exception {
        readyMvc();
        mvc.perform(get("/api/canary/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("就绪部署态：nativeReady/capabilityDigest/active bundle canary 段/决策计数全投影")
    void projectsReadyStatusWithCanarySection() throws Exception {
        bundles.activate(new ConfigBundle(UUID.randomUUID(),
                new Digest("a".repeat(64)), 1L, Map.of(
                        "policy_version", "pv-test",
                        "canary", Map.of("percent", 1, "max_native_runs", 20,
                                "whitelist", List.of("g:1"))),
                "release-operator", Instant.parse("2026-09-08T00:00:00Z")));
        decisions.nativeCount = 3;
        readyMvc();

        mvc.perform(get("/api/canary/status").header(AUTH, "Bearer " + BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeReady").value(true))
                .andExpect(jsonPath("$.capabilityDigest").value("c".repeat(64)))
                .andExpect(jsonPath("$.missing").doesNotExist())
                .andExpect(jsonPath("$.activeBundleDigest").value("a".repeat(64)))
                .andExpect(jsonPath("$.canary.percent").value(1))
                .andExpect(jsonPath("$.canary.maxNativeRuns").value(20))
                .andExpect(jsonPath("$.canary.whitelistSize").value(1))
                .andExpect(jsonPath("$.nativeDecisions").value(3));
    }

    @Test
    @DisplayName("无 active bundle：activeBundleDigest/canary 双 null，其余照投")
    void projectsNullsWhenNoActiveBundle() throws Exception {
        decisions.nativeCount = 0;
        readyMvc();

        mvc.perform(get("/api/canary/status").header(AUTH, "Bearer " + BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeReady").value(true))
                .andExpect(jsonPath("$.activeBundleDigest").doesNotExist())
                .andExpect(jsonPath("$.canary").doesNotExist())
                .andExpect(jsonPath("$.nativeDecisions").value(0));
    }

    @Test
    @DisplayName("缺件部署态：nativeReady=false + missing 清单 + 无 capabilityDigest")
    void projectsMissingWhenCapabilityIncomplete() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new CanaryStatusController(bundles,
                decisions, windows,
                new Capability(false, List.of("metricsAgent", "metricsExpr"), null),
                BEARER)).build();

        mvc.perform(get("/api/canary/status").header(AUTH, "Bearer " + BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeReady").value(false))
                .andExpect(jsonPath("$.missing[0]").value("metricsAgent"))
                .andExpect(jsonPath("$.missing[1]").value("metricsExpr"))
                .andExpect(jsonPath("$.capabilityDigest").doesNotExist());
    }

    @Test
    @DisplayName("窗序列查询面：rolloutId+candidateDigest 双参 → 按 seq 升序投影；单参 → 400")
    void windowSeriesQueryFace() throws Exception {
        UUID rollout = UUID.randomUUID();
        String candidate = "b".repeat(64);
        windows.rows.add(new CanaryWindowVerdictRepository.VerdictRow(rollout, candidate,
                "p".repeat(64), "d".repeat(64), 1, 10, 2,
                Instant.parse("2026-09-08T02:00:00Z"), Instant.parse("2026-09-09T02:00:00Z"),
                "LIVE_CANARY", 5, Map.of("eligible_incidents", 5), Map.of(),
                Map.of("gate", "PASS"), Map.of("gate", "PASS"), Boolean.TRUE, null,
                "PASS", List.of(), Instant.parse("2026-09-09T02:00:01Z")));
        windows.rows.add(new CanaryWindowVerdictRepository.VerdictRow(rollout, candidate,
                "p".repeat(64), "d".repeat(64), 1, 10, 1,
                Instant.parse("2026-09-08T00:00:00Z"), Instant.parse("2026-09-09T00:00:00Z"),
                "LIVE_CANARY", 5, Map.of("eligible_incidents", 5), Map.of(),
                Map.of("gate", "PASS"), Map.of("gate", "PASS"), Boolean.TRUE, null,
                "PASS", List.of(), Instant.parse("2026-09-09T00:00:01Z")));
        readyMvc();

        mvc.perform(get("/api/canary/status").header(AUTH, "Bearer " + BEARER)
                        .param("rolloutId", rollout.toString())
                        .param("candidateDigest", candidate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].windowSeq").value(1))
                .andExpect(jsonPath("$.windows[0].verdict").value("PASS"))
                .andExpect(jsonPath("$.windows[1].windowSeq").value(2));

        mvc.perform(get("/api/canary/status").header(AUTH, "Bearer " + BEARER)
                        .param("rolloutId", rollout.toString()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ fakes

    /** 测试内存认账面（ConfigBundleControllerTest.InMemoryBundles 同构最小面） */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        Digest active;

        @Override
        public long nextRevision() {
            return rows.size() + 1;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            return rows.add(bundle);
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
            return Optional.empty();
        }

        @Override
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at) {
            active = toDigest;
            return true;
        }

        void activate(ConfigBundle bundle) {
            rows.add(bundle);
            active = bundle.bundleDigest();
        }
    }

    static final class CountingDecisions implements CanaryDecisionLogRepository {
        long nativeCount;

        @Override
        public long countNativeDecisions() {
            return nativeCount;
        }

        @Override
        public void append(DecisionRow row) {
            throw new UnsupportedOperationException("status 只读面不落审计行");
        }
    }

    static final class InMemoryWindows implements CanaryWindowVerdictRepository {
        final List<VerdictRow> rows = new ArrayList<>();
        final Map<UUID, List<VerdictRow>> queried = new LinkedHashMap<>();

        @Override
        public boolean append(VerdictRow row) {
            return rows.add(row);
        }

        @Override
        public List<VerdictRow> findByRollout(UUID rolloutId, String candidateDigest) {
            queried.computeIfAbsent(rolloutId, id -> new ArrayList<>());
            return rows.stream()
                    .filter(r -> r.rolloutId().equals(rolloutId)
                            && r.candidateDigest().equals(candidateDigest))
                    .sorted(java.util.Comparator.comparingInt(VerdictRow::windowSeq))
                    .toList();
        }
    }
}
