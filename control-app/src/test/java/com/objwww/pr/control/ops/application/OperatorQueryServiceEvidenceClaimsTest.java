package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.ops.interfaces.OperatorApiController;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A1：Case 详情 evidence/claims 死占位接通（拆解文档 §二映射表逐键）——
 * runId null → 空列表；evidence 白名单摘要行（summary 无源显 null、taskId null 不给键、
 * window 任一端 null → null）；claims 按 claimKey 字典序；conflictNote 三分支；
 * 白名单自查：响应 JSON 无 canonicalPayload 键。内存假件 + standalone MockMvc。
 */
class OperatorQueryServiceEvidenceClaimsTest {

    private static final Supplier<Instant> CLOCK =
            () -> Instant.parse("2026-09-12T10:00:00Z");

    private final InMemoryOperatorCases repo = new InMemoryOperatorCases();
    private final OperatorCaseService service = new OperatorCaseService(repo, CLOCK);
    private final InMemoryEvidenceRepository evidenceRepository = new InMemoryEvidenceRepository();
    private final InMemoryClaimStore claimStore = new InMemoryClaimStore();
    private final OperatorQueryService query =
            new OperatorQueryService(repo, CLOCK, evidenceRepository, claimStore);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", null, List.of()));
    }

    // ------------------------------------------------------------------ runId null → 空列表

    @Test
    void detailWithNullRunIdReturnsEmptyEvidenceClaimsAndNullConflictNote() {
        UUID id = seed(null);

        Map<String, Object> out = query.detail(id).orElseThrow();

        assertThat(out.get("evidence")).isEqualTo(List.of());
        assertThat(out.get("claims")).isEqualTo(List.of());
        assertThat(out.get("conflictNote")).isNull();
    }

    // ------------------------------------------------------------------ evidence 投影逐键

    @Test
    void evidenceRowProjectsWhitelistSummaryFacePerKey() {
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        UUID evidenceId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant start = Instant.parse("2026-09-12T09:00:00Z");
        Instant end = Instant.parse("2026-09-12T09:05:00Z");
        evidenceRepository.add(new EvidenceEnvelope(evidenceId, runId, taskId,
                "METRICS_SNAPSHOT", EvidenceEnvelope.SCHEMA_VERSION, 13, "prometheus",
                Map.of(), start, end, "{\"k\":1}", "digest-1"));

        Map<String, Object> out = query.detail(id).orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) out.get("evidence");

        assertThat(evidence).hasSize(1);
        Map<String, Object> row = evidence.get(0);
        assertThat(row.get("id")).isEqualTo(evidenceId.toString());
        assertThat(row.get("type")).isEqualTo("METRICS_SNAPSHOT");
        assertThat(row.get("source")).isEqualTo("prometheus");
        assertThat(row.get("window")).isEqualTo(start + " ~ " + end);
        assertThat(row.get("verify")).isEqualTo("VERIFIED");
        assertThat(row).containsKey("summary");
        assertThat(row.get("summary")).isNull();
        assertThat(row.get("taskId")).isEqualTo(taskId.toString());
        assertThat(row).containsOnlyKeys("id", "type", "source", "window", "verify", "summary", "taskId");
    }

    @Test
    void evidenceRowWindowNullWhenEitherEndNullAndTaskIdKeyOmittedWhenNull() {
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        evidenceRepository.add(new EvidenceEnvelope(UUID.randomUUID(), runId, null,
                "LOG_EXCERPT", EvidenceEnvelope.SCHEMA_VERSION, 13, "loki",
                Map.of(), null, Instant.parse("2026-09-12T09:05:00Z"), "{}", "digest-2"));
        evidenceRepository.add(new EvidenceEnvelope(UUID.randomUUID(), runId, null,
                "LOG_EXCERPT", EvidenceEnvelope.SCHEMA_VERSION, 13, "loki",
                Map.of(), Instant.parse("2026-09-12T09:00:00Z"), null, "{}", "digest-3"));

        Map<String, Object> out = query.detail(id).orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) out.get("evidence");

        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).get("window")).isNull();
        assertThat(evidence.get(1).get("window")).isNull();
        assertThat(evidence.get(0)).doesNotContainKey("taskId");
        assertThat(evidence.get(1)).doesNotContainKey("taskId");
    }

    // ------------------------------------------------------------------ claims 投影逐键 + 排序

    @Test
    void claimsProjectedPerKeyAndSortedByClaimKey() {
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        UUID claimB = UUID.randomUUID();
        UUID claimA = UUID.randomUUID();
        claimStore.add(claimRow(claimB, runId, "key-b", ClaimStatus.TRUE,
                EvidenceBasis.SINGLE_SOURCE, ClaimLifecycle.ACTIVE, "理由-b", List.of("ref-2")));
        claimStore.add(claimRow(claimA, runId, "key-a", ClaimStatus.UNKNOWN,
                EvidenceBasis.MULTI_SOURCE_CONSISTENT, ClaimLifecycle.ACTIVE, "理由-a",
                List.of("ref-1", "ref-0")));

        Map<String, Object> out = query.detail(id).orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claims = (List<Map<String, Object>>) out.get("claims");

        assertThat(claims).hasSize(2);
        Map<String, Object> first = claims.get(0);
        assertThat(first.get("id")).isEqualTo(claimA.toString());
        assertThat(first.get("text")).isEqualTo("理由-a");
        assertThat(first.get("verdict")).isEqualTo("UNKNOWN");
        assertThat(first.get("evidenceRefs")).isEqualTo(List.of("ref-1", "ref-0"));
        assertThat(first).containsOnlyKeys("id", "text", "verdict", "evidenceRefs");
        assertThat(claims.get(1).get("id")).isEqualTo(claimB.toString());
        assertThat(claims.get(1).get("text")).isEqualTo("理由-b");
        assertThat(claims.get(1).get("verdict")).isEqualTo("TRUE");
        assertThat(claims.get(1).get("evidenceRefs")).isEqualTo(List.of("ref-2"));
    }

    // ------------------------------------------------------------------ conflictNote 三分支

    @Test
    void conflictNoteNullWhenNoConflictClaim() {
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        claimStore.add(claimRow(UUID.randomUUID(), runId, "key-a", ClaimStatus.TRUE,
                EvidenceBasis.SINGLE_SOURCE, ClaimLifecycle.ACTIVE, "理由", List.of()));

        Map<String, Object> out = query.detail(id).orElseThrow();

        assertThat(out.get("conflictNote")).isNull();
    }

    @Test
    void conflictNoteJoinsClaimKeysOfActiveMultiSourceConflictClaims() {
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        claimStore.add(claimRow(UUID.randomUUID(), runId, "key-b", ClaimStatus.UNKNOWN,
                EvidenceBasis.MULTI_SOURCE_CONFLICT, ClaimLifecycle.ACTIVE, "冲突-b", List.of()));
        claimStore.add(claimRow(UUID.randomUUID(), runId, "key-a", ClaimStatus.UNKNOWN,
                EvidenceBasis.MULTI_SOURCE_CONFLICT, ClaimLifecycle.ACTIVE, "冲突-a", List.of()));

        Map<String, Object> out = query.detail(id).orElseThrow();

        assertThat(out.get("conflictNote")).isEqualTo("key-a,key-b");
    }

    @Test
    void conflictNoteNullWhenConflictClaimSuperseded() {
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        claimStore.add(claimRow(UUID.randomUUID(), runId, "key-a", ClaimStatus.UNKNOWN,
                EvidenceBasis.MULTI_SOURCE_CONFLICT, ClaimLifecycle.SUPERSEDED, "旧冲突", List.of()));

        Map<String, Object> out = query.detail(id).orElseThrow();

        assertThat(out.get("conflictNote")).isNull();
    }

    // ------------------------------------------------------------------ 白名单：响应 JSON 无 canonicalPayload

    @Test
    void detailResponseJsonContainsNoCanonicalPayloadKey() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new OperatorApiController(query, service))
                .build();
        UUID runId = UUID.randomUUID();
        UUID id = seed(runId);
        evidenceRepository.add(new EvidenceEnvelope(UUID.randomUUID(), runId, UUID.randomUUID(),
                "METRICS_SNAPSHOT", EvidenceEnvelope.SCHEMA_VERSION, 13, "prometheus",
                Map.of(), Instant.parse("2026-09-12T09:00:00Z"),
                Instant.parse("2026-09-12T09:05:00Z"), "{\"secret\":\"payload\"}", "digest-9"));
        claimStore.add(claimRow(UUID.randomUUID(), runId, "key-a", ClaimStatus.TRUE,
                EvidenceBasis.SINGLE_SOURCE, ClaimLifecycle.ACTIVE, "理由", List.of("ref-1")));

        String body = mvc.perform(get("/api/cases/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("canonicalPayload");
        assertThat(body).doesNotContain("secret");
        assertThat(body).contains("\"evidence\":[{").contains("\"claims\":[{");
    }

    // ------------------------------------------------------------------ 种子

    private UUID seed(UUID runId) {
        return service.openOrMerge(new CaseDraft("tenant-1", "fp-" + UUID.randomUUID(),
                "Claim 冲突", "P0", "CLAIM_CONFLICT", runId, "root-cause", "payment-failure",
                Digest.sha256Of("snapshot"), 13, List.of("evidence#81"),
                CLOCK.get().plusSeconds(600), CLOCK.get().plusSeconds(3600),
                "idem-" + UUID.randomUUID())).caseId();
    }

    private static ClaimStore.ClaimRow claimRow(UUID id, UUID runId, String claimKey,
                                                ClaimStatus status, EvidenceBasis basis,
                                                ClaimLifecycle lifecycle, String reason,
                                                List<String> evidenceRefs) {
        return new ClaimStore.ClaimRow(id, runId, "fingerprint", "hash-" + claimKey, claimKey,
                status, basis, lifecycle, reason, "scope", "range", 13,
                List.of("source-1"), evidenceRefs, "policy-v1", "snapshot-digest");
    }
}
