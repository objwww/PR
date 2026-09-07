package com.objwww.pr.control.alert.domain.claim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 报告装配器（AM4 M4-23，纯域确定性——<b>无 LLM 输入口</b>）：只从已冻结 Snapshot
 * 与已裁决 Claim（当前投影 ACTIVE 面）组装报告草案。
 *
 * <p><b>分节依据必须来自 M4-22 裁决状态机而非 LLM 自述</b>（确定/推测分节形式可抄
 * HolmesGPT，依据不可抄）：
 * <ul>
 *   <li>确定节 confirmed —— {@code corroborated()}（≥2 独立来源一致）：status=TRUE
 *       为确认根因候选，status=FALSE 为确认排除（证伪也是"确定"，不是根因）；</li>
 *   <li>推测节 speculative —— SINGLE_SOURCE（含权威源裁决）：推测不冒充确认，
 *       单源 TRUE 也只进推测节；</li>
 *   <li>未决节 unresolved —— status=UNKNOWN（MULTI_SOURCE_CONFLICT，对峙即人工）。</li>
 * </ul>
 * <b>无证据不产确认根因</b>：装配必须绑定冻结快照（null = fail-closed 显式拒绝——
 * 诚实空报告也有快照 digest）；无 Claim 或无双源 TRUE → UNRESOLVED。
 * 结果三态 {@link Outcome}：CONFIRMED（有确认根因且无推测/未决残留）/ PARTIAL（有
 * 确认根因但有残留——证据缺失只能 PARTIAL 不许臆测）/ UNRESOLVED（无确认根因）。
 * 外来快照（非 null 且 ≠ 报告快照）的 Claim 被排除且不计入残留（另一个世界的产出，
 * 不得污染本报告，对齐 E2E-05 跨代不污染）。各节按键排序，输入顺序无关。
 */
public final class ReportAssembler {

    private static final Comparator<ClaimVerdict> ORDER = Comparator
            .comparing(ClaimVerdict::claimKey)
            .thenComparing(ClaimVerdict::scope)
            .thenComparing(ClaimVerdict::timeRange)
            .thenComparingLong(ClaimVerdict::observedGeneration);

    private ReportAssembler() {
    }

    /** 报告结论三态（裁决状态机推导，非 LLM 判定） */
    public enum Outcome { CONFIRMED, PARTIAL, UNRESOLVED }

    /**
     * @param snapshotDigest 报告绑定的冻结快照摘要（必填——无证据不产确认根因）
     * @param activeClaims   已裁决 ACTIVE 投影（各节按裁决状态机分派）
     */
    public static AssembledReport assemble(String snapshotDigest,
            Collection<ClaimVerdict> activeClaims) {
        if (snapshotDigest == null || snapshotDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "报告必须绑定冻结快照——无证据不产确认根因（snapshotDigest 缺失）");
        }
        Objects.requireNonNull(activeClaims, "activeClaims");

        List<ClaimVerdict> confirmed = new ArrayList<>();
        List<ClaimVerdict> speculative = new ArrayList<>();
        List<ClaimVerdict> unresolved = new ArrayList<>();
        int foreign = 0;
        for (ClaimVerdict claim : activeClaims) {
            if (claim.snapshotDigest() != null && !claim.snapshotDigest().equals(snapshotDigest)) {
                foreign++;
                continue;
            }
            if (claim.status() == ClaimStatus.UNKNOWN) {
                unresolved.add(claim);
            } else if (claim.corroborated()) {
                confirmed.add(claim);
            } else {
                speculative.add(claim);
            }
        }
        confirmed.sort(ORDER);
        speculative.sort(ORDER);
        unresolved.sort(ORDER);

        boolean hasConfirmedRootCause = confirmed.stream()
                .anyMatch(c -> c.status() == ClaimStatus.TRUE);
        Outcome outcome;
        if (!hasConfirmedRootCause) {
            outcome = Outcome.UNRESOLVED;
        } else if (speculative.isEmpty() && unresolved.isEmpty()) {
            outcome = Outcome.CONFIRMED;
        } else {
            outcome = Outcome.PARTIAL;
        }
        return new AssembledReport(outcome, snapshotDigest,
                List.copyOf(confirmed), List.copyOf(speculative), List.copyOf(unresolved),
                foreign);
    }

    /** 装配结果（纯值；确认根因 = confirmed 中 status=TRUE 者） */
    public record AssembledReport(
            Outcome outcome,
            String snapshotDigest,
            List<ClaimVerdict> confirmed,
            List<ClaimVerdict> speculative,
            List<ClaimVerdict> unresolved,
            int excludedForeignSnapshotCount) {

        public AssembledReport {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(snapshotDigest, "snapshotDigest");
            confirmed = List.copyOf(Objects.requireNonNull(confirmed, "confirmed"));
            speculative = List.copyOf(Objects.requireNonNull(speculative, "speculative"));
            unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved"));
        }

        /** 是否存在确认根因（corroborated 且 status=TRUE；报告正文唯一可写"确认"的位置） */
        public boolean hasConfirmedRootCause() {
            return confirmed.stream().anyMatch(c -> c.status() == ClaimStatus.TRUE);
        }
    }
}
