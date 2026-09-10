package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 主 Agent FINAL Claim 准入（R7a-2/X5，v2.1 §五；RD05/RX20 面）——纯代码规则，
 * 模型不自评类型晋升：
 * <ul>
 *   <li><b>引用越界拒绝</b>：evidence_refs 只认本 run 已准入工件集（证据行 id +
 *       快照等已知 artifact 键，由执行器装配传入）；越界引用剥离并留痕，不静默
 *       当作有效印证（RX06 结果准入面）；</li>
 *   <li><b>必要证据校验</b>：ROOT_CAUSE 剥离后零有效引用 → 降级 HYPOTHESIS
 *       （机制陈述+引用是必要结构；不满足不确认，可未决结束）；</li>
 *   <li><b>同一来源只算一份</b>：claim 内去重（LinkedHashSet），跨角色复述同一
 *       证据行不构成第二份独立来源——来源身份=证据行 id，谱系在证据行 producer
 *       字段，不在引用计数。</li>
 * </ul>
 */
public final class PrimaryClaimAdmission {

    /** 降级留痕码（admissionNote 语义） */
    public static final String NOTE_DOWNGRADED_NO_EVIDENCE = "DOWNGRADED_NO_VALID_EVIDENCE";
    public static final String NOTE_REFS_STRIPPED = "OUT_OF_RUN_REFS_STRIPPED";

    /** 准入产物：kind 可能被降级、refs 可能被剥离；note 封闭码逗号连接 */
    public record AdmittedClaim(String claimKey, String kind, String statement,
            List<String> evidenceRefs, String admissionNote) {

        public AdmittedClaim {
            Objects.requireNonNull(claimKey, "claimKey");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(statement, "statement");
            evidenceRefs = List.copyOf(evidenceRefs);
            Objects.requireNonNull(admissionNote, "admissionNote");
        }
    }

    public record AdmissionResult(List<AdmittedClaim> claims, int downgraded,
            int strippedRefs) {
    }

    private PrimaryClaimAdmission() {
    }

    /**
     * @param claims    FINAL 分支解析出的 Claim 提案
     * @param validRefs 本 run 已准入工件引用全集（证据行 UUID 串 + 已知 artifact 键）
     */
    public static AdmissionResult admit(List<PrimaryDecision.FinalClaim> claims,
            Set<String> validRefs) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(validRefs, "validRefs");
        List<AdmittedClaim> out = new ArrayList<>();
        int downgraded = 0;
        int stripped = 0;
        for (PrimaryDecision.FinalClaim claim : claims) {
            List<String> note = new ArrayList<>();
            Set<String> kept = new LinkedHashSet<>();
            for (String ref : claim.evidenceRefs()) {
                if (validRefs.contains(ref)) {
                    kept.add(ref);
                } else {
                    stripped++;
                }
            }
            if (kept.size() < claim.evidenceRefs().size()) {
                note.add(NOTE_REFS_STRIPPED);
            }
            String kind = claim.kind() == null ? "HYPOTHESIS" : claim.kind();
            if ("ROOT_CAUSE".equals(kind) && kept.isEmpty()) {
                kind = "HYPOTHESIS";
                note.add(NOTE_DOWNGRADED_NO_EVIDENCE);
                downgraded++;
            }
            out.add(new AdmittedClaim(claim.claimKey(), kind, claim.statement(),
                    List.copyOf(kept), String.join(",", note)));
        }
        return new AdmissionResult(List.copyOf(out), downgraded, stripped);
    }
}
