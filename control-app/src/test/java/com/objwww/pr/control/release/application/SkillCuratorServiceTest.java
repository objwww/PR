package com.objwww.pr.control.release.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationRecovery;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.RcaRunSource;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 源头 L0 面（用例文档 §五"封存资格/候选生成"两行）：RUNNING/FAILED/零证据
 * 轨迹拒绝且零落库；SUCCEEDED+未复核 → S02 REJECTED 隔离；SUCCEEDED+已复核 →
 * DRAFT 且来源可追溯（content.source.run_id=runId）；同源版本重复提交 → S14 幂等。
 */
class SkillCuratorServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T15:00:00Z");

    private MemRuns runs;
    private List<EvidenceEnvelope> evidenceRows;
    private List<InvocationRecovery> toolRows;
    private SkillCandidateServiceTest.MemCandidates candidates;
    private SkillCandidateServiceTest.MemAssets assets;
    private SkillCuratorService curator;

    @BeforeEach
    void setUp() {
        runs = new MemRuns();
        evidenceRows = new ArrayList<>();
        toolRows = new ArrayList<>();
        candidates = new SkillCandidateServiceTest.MemCandidates();
        assets = new SkillCandidateServiceTest.MemAssets();
        curator = new SkillCuratorService(runs, memEvidence(),
                runId -> toolRows,
                new SkillCandidateService(candidates, assets,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 只读证据桩（findByRunId 面返回 rows；写面不支持） */
    private com.objwww.pr.control.alert.domain.evidence.EvidenceRepository memEvidence() {
        return new com.objwww.pr.control.alert.domain.evidence.EvidenceRepository() {
            @Override
            public void insert(EvidenceEnvelope envelope) {
                throw new UnsupportedOperationException("curator 只读");
            }

            @Override
            public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
                return Optional.empty();
            }

            @Override
            public List<EvidenceEnvelope> findByRunId(UUID runId) {
                return evidenceRows.stream().filter(e -> e.runId().equals(runId)).toList();
            }
        };
    }

    private static RcaRun run(RcaRunState state) {
        return new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 0,
                com.objwww.pr.control.alert.domain.model.RunTrigger.INITIAL,
                state, new Digest("a".repeat(64)), NOW.minusSeconds(600),
                NOW.minusSeconds(60), NOW.minusSeconds(600), NOW.minusSeconds(60), null);
    }

    private static EvidenceEnvelope evidence(UUID runId, String type, String payload) {
        UUID id = UUID.randomUUID();
        return new EvidenceEnvelope(id, runId, id, type, "v1", 0, "src",
                Map.of(), null, null, payload, Digest.sha256Of(payload).value());
    }

    private SkillCuratorService.Curation curation(UUID runId, boolean reviewed) {
        return new SkillCuratorService.Curation(runId, "heap-runbook",
                "JvmHeapHigh", "svc-a", reviewed, "skill-curator");
    }

    @Test
    @DisplayName("封存资格：RUNNING/FAILED/零证据轨迹拒绝且零落库（候选与资产双零）")
    void sealedContractRefusals() {
        UUID runningId = UUID.randomUUID();
        runs.rows.put(runningId, run(RcaRunState.RUNNING));
        assertThat(curator.curate(curation(runningId, true)).refusal())
                .contains("封存").contains("RUNNING");

        UUID failedId = UUID.randomUUID();
        runs.rows.put(failedId, run(RcaRunState.FAILED));
        evidenceRows.add(evidence(failedId, "prometheus.instant", "{\"v\":1}"));
        assertThat(curator.curate(curation(failedId, true)).refusal())
                .contains("FAILED").contains("不作正向原料");

        UUID okId = UUID.randomUUID();
        runs.rows.put(okId, run(RcaRunState.SUCCEEDED));
        assertThat(curator.curate(curation(okId, true)).refusal())
                .contains("零证据");

        assertThat(candidates.rows).as("来源契约拒绝零落库（无候选行）").isEmpty();
        assertThat(assets.rows).as("来源契约拒绝零落库（无资产）").isEmpty();
    }

    @Test
    @DisplayName("未复核闭环：SUCCEEDED+UNVERIFIED → S02 REJECTED 隔离（不当可信正向原料）")
    void unverifiedRunLandsRejected() {
        UUID runId = UUID.randomUUID();
        runs.rows.put(runId, run(RcaRunState.SUCCEEDED));
        evidenceRows.add(evidence(runId, "prometheus.instant", "{\"v\":1}"));
        evidenceRows.add(evidence(runId, "prometheus.instant", "{\"v\":2}"));

        SkillCuratorService.Verdict verdict = curator.curate(curation(runId, false));
        assertThat(verdict.refusal()).isNull();
        assertThat(verdict.candidate().status()).isEqualTo(SkillCandidate.ST_REJECTED);
        assertThat(verdict.candidate().failureReason()).contains("S02");
        assertThat(assets.rows).as("未复核不落 SKILL 资产").isEmpty();
    }

    @Test
    @DisplayName("候选生成：已复核闭环 → DRAFT；来源可追溯；步骤=证据类型时序去重")
    void reviewedRunLandsTraceableDraft() {
        UUID runId = UUID.randomUUID();
        runs.rows.put(runId, run(RcaRunState.SUCCEEDED));
        evidenceRows.add(evidence(runId, "prometheus.instant", "{\"q\":\"up\"}"));
        evidenceRows.add(evidence(runId, "logs.aggregate", "{\"n\":3}"));
        evidenceRows.add(evidence(runId, "prometheus.instant", "{\"q\":\"rate\"}"));

        SkillCuratorService.Verdict verdict = curator.curate(curation(runId, true));
        assertThat(verdict.refusal()).isNull();
        SkillCandidate draft = verdict.candidate();
        assertThat(draft.status()).isEqualTo(SkillCandidate.ST_DRAFT);
        assertThat(draft.verificationStatus()).isEqualTo(SkillCandidate.VERIFIED);

        // 来源可追溯：资产 content.source.run_id = 提炼源 run
        com.objwww.pr.control.release.domain.model.ReleaseAsset asset = assets.rows.get(0);
        assertThat(asset.kind()).isEqualTo("SKILL");
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) asset.content().get("source");
        assertThat(source.get("run_id")).isEqualTo(runId.toString());
        assertThat(String.valueOf(asset.content().get("body")))
                .contains("prometheus.instant").contains("logs.aggregate");
    }

    @Test
    @DisplayName("S14 经源头：同一封存源重复提交 → 幂等重放既有行，不堆重复候选")
    void sameSourceVersionIdempotent() {
        UUID runId = UUID.randomUUID();
        runs.rows.put(runId, run(RcaRunState.SUCCEEDED));
        evidenceRows.add(evidence(runId, "prometheus.instant", "{\"q\":\"up\"}"));

        SkillCuratorService.Verdict first = curator.curate(curation(runId, true));
        SkillCuratorService.Verdict second = curator.curate(curation(runId, true));

        assertThat(first.candidate().status()).isEqualTo(SkillCandidate.ST_DRAFT);
        assertThat(second.candidate().id()).isEqualTo(first.candidate().id());
        assertThat(candidates.rows).hasSize(1);
        assertThat(assets.rows).hasSize(1);
    }

    // ------------------------------------------------------------ 内存桩

    static final class MemRuns implements RcaRunSource {
        final Map<UUID, RcaRun> rows = new LinkedHashMap<>();

        @Override
        public Optional<RcaRun> byId(UUID runId) {
            return Optional.ofNullable(rows.get(runId));
        }
    }
}
