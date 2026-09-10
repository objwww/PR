package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.claim.Claim;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;

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
 * <p>黑板契约（EX-A4a F05 收紧）：黑板 = <b>冻结快照成员表</b>，非 run 全量证据——
 * {@code snapshots.find(runId, snapshotDigest)} 缺快照行 fail-closed；
 * {@code membersOf(snapshotId)} 逐成员 {@code evidence.findById} 精确读取（附赠
 * 篡改 verify），<b>缺成员/成员行与证据行身份不符 = 显式失败</b>（不静默跳过）。
 * 冻结后迟到证据结构性不可入黑板；旧快照不可追加成员（P1-05，freeze 幂等面）。
 *
 * <p>断言注记契约：证据信封 scope 中携带断言注记 {@code claim_key}/{@code claim_status}/
 * {@code scope}（无 claim_key 的证据是原始数据不是断言，跳过）；Claim.source 取证据
 * 的来源标签（prometheus/logs/change…）——独立数据源即独立来源，Reducer 的多源佐证
 * 与对峙判定由此获得真实的来源维度。
 *
 * <p>EX-A0 三身份分野（F04）：{@code inputDigest} 是调查输入身份（scope 声明了异于
 * run 输入身份的证据一律排除；null = 本 run 无输入绑定，不做排除）；
 * {@code snapshotDigest} 是输出证据快照身份（必填 fail-closed）。两身份类型不同、
 * 混用编译期拒绝。成员序 = evidence_id 序（稳定）→ 相同黑板相同 Claim 指纹。
 */
public class NativeRcaAgent {

    private final EvidenceRepository evidence;
    private final EvidenceSnapshotRepository snapshots;
    private final ClaimStore claims;
    private final ClaimReducer reducer;

    /** EX-A4a（F05）：快照仓储为必填身份面——成员精确读取的唯一事实源 */
    public NativeRcaAgent(EvidenceRepository evidence,
            EvidenceSnapshotRepository snapshots, ClaimStore claims,
            ClaimReducer reducer) {
        this.evidence = Objects.requireNonNull(evidence);
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.claims = Objects.requireNonNull(claims);
        this.reducer = Objects.requireNonNull(reducer);
    }

    /** 提案结果：本 round 落库的裁决集与指纹（回放比对锚点） */
    public record NativeResult(List<ClaimVerdict> verdicts, List<String> fingerprints) {
    }

    /**
     * 一轮断言推导：冻结黑板 → Claim 集 → Reducer 裁决 → ClaimStore 逐条落投影。
     *
     * @param inputDigest    本 run 冻结的调查输入身份（可空——存量 run 兼容面）
     * @param snapshotDigest 本 run 冻结证据快照（必填，fail-closed）
     * @param generation     本 run 观察代际（进 Claim 分组键）
     */
    public NativeResult investigate(UUID runId, InvestigationInputDigest inputDigest,
            EvidenceSnapshotDigest snapshotDigest, long generation) {
        if (snapshotDigest == null) {
            throw new IllegalArgumentException(
                    "固定 Snapshot 必绑：Native RCA 提案必须绑定冻结快照 digest（fail-closed）");
        }
        EvidenceSnapshotRepository.FrozenSnapshot snapshot =
                snapshots.find(runId, snapshotDigest.value())
                        .orElseThrow(() -> new IllegalStateException(
                                "快照行缺失（黑板契约 fail-closed）: run=" + runId
                                        + " snapshot=" + snapshotDigest.value()));
        List<Claim> assertions = new ArrayList<>();
        for (EvidenceSnapshotRepository.SnapshotMemberRow member
                : snapshots.membersOf(snapshot.snapshotId())) {
            EvidenceEnvelope envelope = evidence.findById(member.evidenceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "快照成员缺证据行（完整性破坏，显式失败）: snapshot="
                                    + snapshot.snapshotId() + " evidence="
                                    + member.evidenceId()));
            if (!member.evidenceType().equals(envelope.evidenceType())
                    || !member.payloadDigest().equals(envelope.payloadDigest())) {
                throw new IllegalStateException(
                        "快照成员身份不符（type/payload_digest 与证据行不一致）: "
                                + member.evidenceId());
            }
            String claimed = stringScope(envelope, "investigation_input_digest");
            if (inputDigest != null && claimed != null
                    && !claimed.equals(inputDigest.value())) {
                continue; // 外来输入身份排除（输入比对输入——F04 混用消灭点）
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
                    envelope.source(), snapshotDigest.value()));
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
