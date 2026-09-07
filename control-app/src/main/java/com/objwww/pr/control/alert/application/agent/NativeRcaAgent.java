package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.claim.Claim;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Native RCA Agent（AM4 M4-30）：消费结构化黑板（带断言注记的证据），确定性提出
 * Claim 经 {@link ClaimReducer} 裁决后落 {@link ClaimStore}——<b>不直接发布报告</b>
 * （结构上无报告/通知出口，报告链 = ReportAssembler 消费已裁决 Claim）。
 *
 * <p>黑板契约：证据信封 scope 中携带断言注记 {@code claim_key}/{@code claim_status}/
 * {@code scope}（无 claim_key 的证据是原始数据不是断言，跳过）；Claim.source 取证据
 * 的来源标签（prometheus/logs/change…）——独立数据源即独立来源，Reducer 的多源佐证
 * 与对峙判定由此获得真实的来源维度。
 *
 * <p>固定 Snapshot 绑定（验收"固定 Snapshot 回放可比较"）：快照 digest 是本 Agent 的
 * 必填身份面（null fail-closed）；scope 声明了异于本快照的证据一律排除（跨代/跨快照
 * 产出不污染本 run 断言，对齐 E2E-05）；确定性推导 + Reducer 排序归并 ⇒ 相同黑板
 * 相同 Claim 指纹，LLM 面可在 M4-33 Replay Runner 以 mock 工具置换后精确比对。
 */
public class NativeRcaAgent {

    private final EvidenceRepository evidence;
    private final ClaimStore claims;
    private final ClaimReducer reducer;

    public NativeRcaAgent(EvidenceRepository evidence, ClaimStore claims,
            ClaimReducer reducer) {
        this.evidence = Objects.requireNonNull(evidence);
        this.claims = Objects.requireNonNull(claims);
        this.reducer = Objects.requireNonNull(reducer);
    }

    /** 提案结果：本 round 落库的裁决集与指纹（回放比对锚点） */
    public record NativeResult(List<ClaimVerdict> verdicts, List<String> fingerprints) {
    }

    /**
     * 一轮断言推导：黑板 → Claim 集 → Reducer 裁决 → ClaimStore 逐条落投影。
     *
     * @param snapshotDigest 本 run 冻结快照（必填，fail-closed）
     * @param generation     本 run 观察代际（进 Claim 分组键）
     */
    public NativeResult investigate(UUID runId, String snapshotDigest, long generation) {
        if (snapshotDigest == null || snapshotDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "固定 Snapshot 必绑：Native RCA 提案必须绑定冻结快照 digest（fail-closed）");
        }
        List<Claim> assertions = new ArrayList<>();
        for (EvidenceEnvelope envelope : evidence.findByRunId(runId)) {
            String claimed = stringScope(envelope, "input_snapshot_digest");
            if (claimed != null && !claimed.equals(snapshotDigest)) {
                continue; // 外来快照排除
            }
            String claimKey = stringScope(envelope, "claim_key");
            if (claimKey == null || claimKey.isBlank()) {
                continue; // 原始数据非断言
            }
            assertions.add(new Claim(claimKey,
                    parseStatus(stringScope(envelope, "claim_status")),
                    stringScope(envelope, "reason") == null
                            ? "evidence-derived" : stringScope(envelope, "reason"),
                    stringScope(envelope, "scope") == null
                            ? "" : stringScope(envelope, "scope"),
                    stringScope(envelope, "time_range") == null
                            ? Claim.TIME_RANGE_UNKNOWN : stringScope(envelope, "time_range"),
                    envelope.observedGeneration(),
                    List.of(envelope.evidenceId().toString()),
                    envelope.source(), snapshotDigest));
        }

        List<ClaimVerdict> verdicts = reducer.reduce(assertions);
        List<String> fingerprints = new ArrayList<>();
        for (ClaimVerdict verdict : verdicts) {
            claims.append(runId, verdict);
            fingerprints.add(verdict.fingerprint());
        }
        return new NativeResult(List.copyOf(verdicts), List.copyOf(fingerprints));
    }

    private static String stringScope(EvidenceEnvelope envelope, String key) {
        Object value = envelope.scope().get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static ClaimStatus parseStatus(String status) {
        if (status == null) {
            return ClaimStatus.UNKNOWN;
        }
        return ClaimStatus.valueOf(status);
    }
}
