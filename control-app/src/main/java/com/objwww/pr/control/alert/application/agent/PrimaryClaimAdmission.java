package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 主 Agent FINAL Claim 准入（R7a-2/X5，v2.1 §五；RD05/RX20 面；A0 补充方案 §2 升版）——
 * 纯代码规则，模型不自评类型晋升：
 * <ul>
 *   <li><b>引用越界拒绝</b>：evidence_refs 只认本 run 已准入工件集（证据行 id +
 *       快照等已知 artifact 键，由执行器装配传入）；越界引用剥离并留痕，不静默
 *       当作有效印证（RX06 结果准入面）；</li>
 *   <li><b>必要证据校验</b>：ROOT_CAUSE 剥离后零有效引用 → 降级 HYPOTHESIS；
 *       有引用但零 SUPPORTS（支持关系未确认）同样降级——引用存在≠证据支持
 *       （A0 补充方案 §2 第 4 条：无法确认支持关系时降为 UNKNOWN/HYPOTHESIS）；</li>
 *   <li><b>同一来源只算一份</b>：claim 内去重（LinkedHashSet），跨角色复述同一
 *       证据行不构成第二份独立来源——来源身份=证据行 source 标签，谱系在证据行
 *       producer 字段，不在引用计数；同一底层信号的多工具读取在投影面按 source
 *       归并（PrimaryFinalClaimProjector），不按 toolId 伪造独立性；</li>
 *   <li><b>引用作用面（A0 补充方案 §2 第 1/2/3 条）</b>：每条引用的断言作用
 *       SUPPORTS/REFUTES/CONTEXT 由模型<b>提议</b>、由本面确定性授予——未声明的
 *       引用一律 CONTEXT（支持关系未确认，不自动算支持）；确定性计数检查：
 *       全量日志聚合计数（logs.aggregate severity=ALL）不能成为错误/失败断言的
 *       支持证据（强制 CONTEXT）；累计型计数器即时值（metrics.metric_value 且
 *       指标名 *_total）不能冒充窗口增量（强制 CONTEXT）；locator 载荷定位
 *       解析失败剥离留痕。普通上下文证据不计入支持来源数。</li>
 * </ul>
 */
public final class PrimaryClaimAdmission {

    /** 降级留痕码（admissionNote 语义） */
    public static final String NOTE_DOWNGRADED_NO_EVIDENCE = "DOWNGRADED_NO_VALID_EVIDENCE";
    public static final String NOTE_REFS_STRIPPED = "OUT_OF_RUN_REFS_STRIPPED";
    /** A0 补充方案 §2：引用未声明作用 → CONTEXT（支持关系未确认） */
    public static final String NOTE_SUPPORT_UNDECLARED = "SUPPORT_ROLE_UNDECLARED_CONTEXT";
    /** AS-01/AS-06：全量聚合计数无失败语义，不能支持错误/根因断言 */
    public static final String NOTE_ALL_COUNT_CONTEXT = "ALL_COUNT_NOT_ERROR_EVIDENCE";
    /** AS-07：累计计数器即时值无窗口增量语义 */
    public static final String NOTE_COUNTER_CONTEXT = "CUMULATIVE_COUNTER_NO_WINDOW_DELTA";
    /** locator 载荷定位解析失败：剥离定位，不影响作用判定本身 */
    public static final String NOTE_LOCATOR_UNRESOLVED = "LOCATOR_UNRESOLVED";
    /** A0 补充方案 §2 第 4 条：有引用但零确认支持 → 降级 */
    public static final String NOTE_SUPPORT_UNCONFIRMED = "SUPPORT_UNCONFIRMED_DOWNGRADED";

    /** 引用作用封闭集（模型可提议，最终判定由准入/投影确定性授予） */
    public enum RefRole {SUPPORTS, REFUTES, CONTEXT}

    /** 单条引用的准入判定（ref 已确认在本 run 工件集内） */
    public record RefVerdict(String ref, RefRole role, String locator, String note) {
        public RefVerdict {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(note, "note");
        }
    }

    /** 准入产物：kind 可能被降级、refs 可能被剥离；note 封闭码逗号连接 */
    public record AdmittedClaim(String claimKey, String kind, String statement,
            List<String> evidenceRefs, List<RefVerdict> refVerdicts, String admissionNote) {

        /** 兼容构造：无判定面（旧调用方/测试；verdicts=按引用序全 CONTEXT） */
        public AdmittedClaim(String claimKey, String kind, String statement,
                List<String> evidenceRefs, String admissionNote) {
            this(claimKey, kind, statement, evidenceRefs,
                    evidenceRefs.stream()
                            .map(ref -> new RefVerdict(ref, RefRole.CONTEXT, null,
                                    NOTE_SUPPORT_UNDECLARED))
                            .toList(),
                    admissionNote);
        }

        public AdmittedClaim {
            Objects.requireNonNull(claimKey, "claimKey");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(statement, "statement");
            evidenceRefs = List.copyOf(evidenceRefs);
            refVerdicts = List.copyOf(refVerdicts);
            Objects.requireNonNull(admissionNote, "admissionNote");
        }

        /** 是否存在确认支持（SUPPORTS 判定） */
        public boolean hasSupport() {
            return refVerdicts.stream().anyMatch(v -> v.role() == RefRole.SUPPORTS);
        }
    }

    public record AdmissionResult(List<AdmittedClaim> claims, int downgraded,
            int strippedRefs) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private PrimaryClaimAdmission() {
    }

    /**
     * 兼容入口（无证据仓）：不做载荷面确定性检查，作用面按未声明处理。
     *
     * @param claims    FINAL 分支解析出的 Claim 提案
     * @param validRefs 本 run 已准入工件引用全集（证据行 UUID 串 + 已知 artifact 键）
     */
    public static AdmissionResult admit(List<PrimaryDecision.FinalClaim> claims,
            Set<String> validRefs) {
        return admit(claims, validRefs, null, null);
    }

    /**
     * 全量准入（A0 补充方案 §2）：validRefs 边界 + 作用面授予 + 载荷确定性检查。
     *
     * @param evidence 证据仓（可空=跳过载荷面检查，仅测试兼容入口用 null）
     * @param runId    本 run（证据行归属校验面；可空同上）
     */
    public static AdmissionResult admit(List<PrimaryDecision.FinalClaim> claims,
            Set<String> validRefs, EvidenceRepository evidence, UUID runId) {
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
            // 模型作用提案：ref → (role, locator)；越界引用的提案条目一并丢弃
            Map<String, PrimaryDecision.EvidenceRole> proposed = new LinkedHashMap<>();
            for (PrimaryDecision.EvidenceRole role : claim.evidenceRoles()) {
                if (kept.contains(role.ref()) && !proposed.containsKey(role.ref())) {
                    proposed.put(role.ref(), role);
                }
            }
            List<RefVerdict> verdicts = new ArrayList<>();
            for (String ref : kept) {
                verdicts.add(verdictFor(ref, proposed.get(ref), evidence, runId, note));
            }
            String kind = claim.kind() == null ? "HYPOTHESIS" : claim.kind();
            if ("ROOT_CAUSE".equals(kind) && !hasSupport(verdicts)) {
                kind = "HYPOTHESIS";
                note.add(kept.isEmpty()
                        ? NOTE_DOWNGRADED_NO_EVIDENCE : NOTE_SUPPORT_UNCONFIRMED);
                downgraded++;
            }
            out.add(new AdmittedClaim(claim.claimKey(), kind, claim.statement(),
                    List.copyOf(kept), List.copyOf(verdicts), String.join(",", note)));
        }
        return new AdmissionResult(List.copyOf(out), downgraded, stripped);
    }

    private static boolean hasSupport(List<RefVerdict> verdicts) {
        return verdicts.stream().anyMatch(v -> v.role() == RefRole.SUPPORTS);
    }

    /** 单条引用作用授予：未声明→CONTEXT；SUPPORTS 过确定性计数检查与 locator 校验 */
    private static RefVerdict verdictFor(String ref,
            PrimaryDecision.EvidenceRole proposed,
            EvidenceRepository evidence, UUID runId, List<String> claimNotes) {
        if (proposed == null) {
            return new RefVerdict(ref, RefRole.CONTEXT, null, NOTE_SUPPORT_UNDECLARED);
        }
        RefRole role = RefRole.valueOf(proposed.role());
        String locator = proposed.locator();
        String note = "";
        EvidenceEnvelope row = evidenceRow(evidence, runId, ref);
        if (row != null) {
            JsonNode payload = payloadOf(row);
            if (role == RefRole.SUPPORTS) {
                String forced = forcedContextReason(row, payload);
                if (forced != null) {
                    role = RefRole.CONTEXT;
                    note = forced;
                    claimNotes.add(forced);
                }
            }
            if (locator != null && !resolves(payload, locator)) {
                note = note.isEmpty() ? NOTE_LOCATOR_UNRESOLVED
                        : note + "," + NOTE_LOCATOR_UNRESOLVED;
                locator = null;
                claimNotes.add(NOTE_LOCATOR_UNRESOLVED);
            }
        }
        return new RefVerdict(ref, role, locator, note);
    }

    /**
     * 确定性计数检查（A0 补充方案 §2 第 2 条）：返回强制 CONTEXT 的留痕码，无则 null。
     * <ul>
     *   <li>logs.aggregate 且 severity=ALL：全量计数无失败语义（AS-01/AS-06——
     *       100 条全量日志不能支持"100 次错误"）；</li>
     *   <li>metrics.metric_value 且指标名 *_total：累计计数器即时值无窗口增量语义
     *       （AS-07——历史累计值高不证明窗内正在失败）。</li>
     * </ul>
     */
    private static String forcedContextReason(EvidenceEnvelope row, JsonNode payload) {
        if ("logs.aggregate".equals(row.evidenceType())
                && payload.path("data").path("severity").asText("ALL").equals("ALL")) {
            return NOTE_ALL_COUNT_CONTEXT;
        }
        if ("metrics.metric_value".equals(row.evidenceType())) {
            // Prometheus 原文透传：指标名在 data.result[0].metric.__name__
            String metric = payload.path("data").path("result").path(0)
                    .path("metric").path("__name__").asText("");
            if (metric.endsWith("_total")) {
                return NOTE_COUNTER_CONTEXT;
            }
        }
        return null;
    }

    /** 证据行取回：非 UUID 引用（编译期 artifact 键）与跨 run/缺失行返回 null */
    private static EvidenceEnvelope evidenceRow(EvidenceRepository evidence, UUID runId,
            String ref) {
        if (evidence == null || runId == null) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(ref);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
        return evidence.findById(id)
                .filter(row -> runId.equals(row.runId()))
                .orElse(null);
    }

    private static JsonNode payloadOf(EvidenceEnvelope row) {
        try {
            return JSON.readTree(row.canonicalPayload());
        } catch (IOException e) {
            return JSON.nullNode();
        }
    }

    /** locator 解析：点分路径走对象键，数字段走数组下标（载荷面"字段真实存在"校验） */
    private static boolean resolves(JsonNode payload, String locator) {
        JsonNode node = payload;
        for (String segment : locator.split("\\.")) {
            if (node == null) {
                return false;
            }
            if (node.isArray() && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                node = index < node.size() ? node.get(index) : null;
            } else {
                node = node.path(segment);
            }
        }
        return node != null && !node.isMissingNode() && !node.isNull();
    }
}
