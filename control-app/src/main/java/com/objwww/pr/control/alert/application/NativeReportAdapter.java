package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.ReportAssembler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * NativeReportAdapter（M6-01 落点 6）：{@link ReportAssembler.AssembledReport} 三态
 * （CONFIRMED/PARTIAL/UNRESOLVED）→ v2 证据包适配——纯确定性映射（无 LLM 输入口）：
 * <ul>
 *   <li>裁决状态机分节照抄进 claims[]（claim_type=claimKey、component=scope、
 *       fault_type=证据基础、symptom_codes=症状码评分面（{@link ClaimVerdict#symptomCodes()}，
 *       未声明=诚实空数组）、evidence_sources=证据来源标签（审计面保留）、
 *       evidence_refs=证据引用）——不改 FUT-49 两轴语义。symptom_codes 是评分契约
 *       槽位（symptom_coverage 取此），禁止来源标签冒充（BA-158 同族实证：批
 *       aa7f25b4 旧实现 symptom_codes=sources → tp=0/fp=99/fn=60 结构性恒 miss）；</li>
 *   <li>root_cause：确认根因（confirmed 中首个 status=TRUE）携带结构化三元组
 *       （{@link ClaimVerdict#rootCause()}，V147 贯通面）→ 直填 canonical
 *       component/fault_type/reason_code；确认根因未携带三元组或无确认根因 →
 *       诚实 unknown 三元组（unknown/unresolved/NO_CONFIRMED_ROOT_CAUSE），
 *       不拿 scope/claimKey 冒充评分面（旧实现的结构性 miss 根因）；</li>
 *   <li>外层套 Holmes 形状 {@code {"engine":"NATIVE","analysis":"<v2包>"}}——engine
 *       标记随脱敏原文落 CAS 对账（rca_run.engine 列仍是 DB 权威），内层包保持
 *       schema 纯度可直接过 {@code EvidencePackageValidator} 结构验证链。</li>
 * </ul>
 * 发布通道不在此类（"不发明新发布通道"）：适配产物经执行器 → finishTask 共用出口。
 *
 * @author wanghua
 * @date 2026-09-08
 */
public final class NativeReportAdapter {

    /** 外层 engine 标记值（随 raw 落档；DB 权威仍在 rca_run.engine 列） */
    public static final String ENGINE = "NATIVE";

    /** 未知根因诚实三元组（schema 合规，语义=无确认根因） */
    static final String UNKNOWN_COMPONENT = "unknown";
    static final String UNRESOLVED_FAULT_TYPE = "unresolved";
    static final String NO_ROOT_CAUSE_REASON = "NO_CONFIRMED_ROOT_CAUSE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NativeReportAdapter() {
    }

    /** 适配产物：outerJson = 引擎标记外层（validator/raw 输入）；analysisJson = 内层 v2 包 */
    public record Adapted(String outerJson, String analysisJson) {
        public Adapted {
            Objects.requireNonNull(outerJson, "outerJson");
            Objects.requireNonNull(analysisJson, "analysisJson");
        }
    }

    public static Adapted adapt(ReportAssembler.AssembledReport report) {
        Objects.requireNonNull(report, "report");
        String analysis = analysisJson(report);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("engine", ENGINE);
        outer.put("analysis", analysis);
        return new Adapted(Json.write(outer), analysis);
    }

    // ------------------------------------------------------------------ 内层 v2 包装配

    private static String analysisJson(ReportAssembler.AssembledReport report) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("schema_version", 2);
        pkg.put("summary", summaryZh(report));
        pkg.put("root_cause", rootCause(report));
        pkg.put("claims", claims(report));
        pkg.put("evidence", evidenceLines(report));
        pkg.put("impact", impactZh(report));
        pkg.put("remediation", remediationZh(report));
        pkg.put("references", List.of());
        return Json.write(pkg);
    }

    /** 确认根因断言（confirmed 中首个 status=TRUE；三态组装共用此入口，判定口径单一） */
    private static Optional<ClaimVerdict> confirmedRoot(ReportAssembler.AssembledReport report) {
        return report.confirmed().stream()
                .filter(c -> c.status() == ClaimStatus.TRUE)
                .findFirst();
    }

    /**
     * 一段话摘要（六要素自然段，确定性合成，全部取自真实裁决数据，零臆造）：
     * 发生了什么（症状断言的告警中文名）→ 根因（确认断言三元组经词典译为中文
     * 自然陈述，裸英文码/证据引用尾注不进正文）→ 凭什么判断（证据引用数+中文
     * 来源名+单/多源基础）→ 影响多大（从确认结论陈述提取的量化事实，提取不到
     * 如实说未量化）→ 有多大把握（装配 outcome 人话解释）→ 建议怎么办
     * （按故障类型给通用处置方向并标注口径）。UNRESOLVED 时如实写"未确认根因"
     * +首条推测线索，不编造结论。
     */
    private static String summaryZh(ReportAssembler.AssembledReport report) {
        java.util.LinkedHashSet<String> symptoms = new java.util.LinkedHashSet<>();
        for (ClaimVerdict c : allClaims(report)) {
            if (c.symptomCodes() != null) {
                symptoms.addAll(c.symptomCodes());
            }
        }
        StringBuilder sb = new StringBuilder();
        if (symptoms.isEmpty()) {
            sb.append("本次告警触发后，自动调查");
        } else {
            List<String> names = symptoms.stream()
                    .map(RootCauseZhDictionary::symptomName).toList();
            sb.append("告警「").append(String.join("、", names)).append("」触发后，自动调查");
        }
        Optional<ClaimVerdict> root = confirmedRoot(report);
        if (root.isPresent() && root.get().rootCause() != null) {
            com.objwww.pr.control.alert.domain.model.TypedRootCause t = root.get().rootCause();
            RootCauseZhDictionary.FaultTypeZh zh =
                    RootCauseZhDictionary.faultType(t.faultType());
            String statement = stripCitationFace(root.get().reason());
            sb.append("确认根因：").append(RootCauseZhDictionary.componentName(t.component()))
                    .append("发生「").append(RootCauseZhDictionary.faultTypeName(t.faultType()))
                    .append("」");
            if (zh != null) {
                sb.append("（").append(zh.explanation()).append("）");
            }
            if (!statement.isBlank()) {
                sb.append("：").append(statement);
            }
            sb.append("。判断依据：").append(root.get().evidenceRefs().size())
                    .append(" 条证据引用");
            if (!root.get().sources().isEmpty()) {
                List<String> sourceNames = root.get().sources().stream()
                        .map(RootCauseZhDictionary::sourceName).distinct().toList();
                sb.append("，来自 ").append(String.join("、", sourceNames));
            }
            sb.append(root.get().evidenceBasis().name().equals("MULTI_SOURCE_CONSISTENT")
                    ? "，多源互证一致" : "，仅单一来源");
            sb.append("。影响面：").append(impactFact(statement));
        } else if (root.isPresent()) {
            sb.append("已有确认结论（未携带结构化根因三元组）：")
                    .append(stripCitationFace(root.get().reason()));
            sb.append("。影响面未量化，见下方证据明细");
        } else {
            sb.append("未确认根因");
            if (!report.speculative().isEmpty()) {
                sb.append("；现有推测：")
                        .append(stripCitationFace(report.speculative().get(0).reason()));
            }
            sb.append("；需补充取证或人工介入。影响面未量化，见下方证据明细");
        }
        sb.append("。结论置信度：").append(switch (report.outcome()) {
            case CONFIRMED -> "高——全部断言闭环，可据以处置";
            case PARTIAL -> "中——仍有 " + (report.speculative().size()
                    + report.unresolved().size()) + " 条断言未闭环，建议补充取证后再处置";
            case UNRESOLVED -> "未定论——无确认根因，不建议据此处置";
        });
        if (root.isPresent() && root.get().rootCause() != null) {
            RootCauseZhDictionary.FaultTypeZh zh =
                    RootCauseZhDictionary.faultType(root.get().rootCause().faultType());
            sb.append("。处置建议：").append(zh == null
                    ? "该故障类型暂无预置处置方向，请按下方证据明细人工研判"
                    : zh.remediation() + "（通用处置方向，按故障类型给出，非本系统执行）");
        }
        sb.append("。");
        return sb.toString();
    }

    /**
     * 投影期合成的引用尾注清洗：claim reason 尾部带 {@code {ref:ROLE,...}} 判定留痕
     * （审计面，见 PrimaryFinalClaimProjector.verdictFace）——人读摘要/影响提取前
     * 确定性剥除（uuid 引用只活在 evidence_refs 字段，不进正文）。
     */
    static String stripCitationFace(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.replaceAll(
                "\\s*\\{[^{}]*:(?:SUPPORTS|REFUTES|CONTEXT)[^{}]*}", "").trim();
    }

    /**
     * 影响事实提取（确定性，零臆造）：从清洗后的确认结论陈述中挑含数字的分句
     * （如"重复订单计数升至 2"），最多两句；无量化事实 → 诚实说未量化。
     */
    static String impactFact(String cleanedStatement) {
        if (cleanedStatement == null || cleanedStatement.isBlank()) {
            return "未量化，见下方证据明细";
        }
        List<String> quantified = new java.util.ArrayList<>();
        for (String segment : cleanedStatement.split("[。；;\\n]")) {
            String seg = segment.trim();
            if (!seg.isEmpty() && seg.chars().anyMatch(Character::isDigit)) {
                quantified.add(seg);
            }
            if (quantified.size() == 2) {
                break;
            }
        }
        return quantified.isEmpty() ? "未量化，见下方证据明细" : String.join("；", quantified);
    }

    /** 影响分节：确认根因 → 从证据陈述提取的量化事实（提取不到如实说）；无确认根因保持诚实边界 */
    private static String impactZh(ReportAssembler.AssembledReport report) {
        Optional<ClaimVerdict> root = confirmedRoot(report);
        if (root.isPresent() && root.get().rootCause() != null) {
            return "影响面（提取自确认结论的证据陈述，未量化项不臆造）："
                    + impactFact(stripCitationFace(root.get().reason()));
        }
        return "确定性链不产出影响面评估（M6-01 边界），见 claims 分节";
    }

    /** 处置建议分节：按故障类型查词典给通用处置方向（标注口径，不冒充本系统动作） */
    private static String remediationZh(ReportAssembler.AssembledReport report) {
        Optional<ClaimVerdict> root = confirmedRoot(report);
        if (root.isPresent() && root.get().rootCause() != null) {
            RootCauseZhDictionary.FaultTypeZh zh =
                    RootCauseZhDictionary.faultType(root.get().rootCause().faultType());
            if (zh != null) {
                return "通用处置方向（按故障类型「" + zh.name() + "」给出，具体执行需走人工审批链）："
                        + zh.remediation();
            }
            return "该故障类型未登记通用处置方向，请按 claims 证据人工研判";
        }
        return "确定性链不生成修复建议，见 claims 证据引用";
    }

    /**
     * root_cause 三元组：确认根因携带结构化三元组（{@link ClaimVerdict#rootCause()}）
     * → 直填 canonical 码（评分器词表等值命中面）；确认根因未携带三元组或无确认
     * 根因 → 诚实 unknown（不臆测、不拿 scope/claimKey 冒充——旧实现把恒 "primary"
     * 的 scope 与 "c1" 式 claimKey 塞进 component/fault_type，词表恒 miss）。
     */
    private static Map<String, Object> rootCause(ReportAssembler.AssembledReport report) {
        Optional<ClaimVerdict> root = confirmedRoot(report);
        Map<String, Object> rc = new LinkedHashMap<>();
        if (root.isPresent() && root.get().rootCause() != null) {
            com.objwww.pr.control.alert.domain.model.TypedRootCause triple =
                    root.get().rootCause();
            rc.put("component", triple.component());
            rc.put("fault_type", triple.faultType());
            rc.put("reason_code", triple.reasonCode());
        } else {
            rc.put("component", UNKNOWN_COMPONENT);
            rc.put("fault_type", UNRESOLVED_FAULT_TYPE);
            rc.put("reason_code", NO_ROOT_CAUSE_REASON);
        }
        return rc;
    }

    /** 三节全量 claims（裁决状态机分节照抄，规格 = EvidencePackageV2.ReportClaim 形状） */
    private static List<Map<String, Object>> claims(ReportAssembler.AssembledReport report) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ClaimVerdict claim : report.confirmed()) {
            out.add(claimEntry(claim));
        }
        for (ClaimVerdict claim : report.speculative()) {
            out.add(claimEntry(claim));
        }
        for (ClaimVerdict claim : report.unresolved()) {
            out.add(claimEntry(claim));
        }
        return out;
    }

    private static Map<String, Object> claimEntry(ClaimVerdict claim) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("claim_type", claim.claimKey());
        entry.put("status", claim.status().name());
        // 人读面：断言类型（ROOT_CAUSE/HYPOTHESIS/SYMPTOM/EXCLUSION）与原文陈述
        // （reason=模型陈述+准入注记，投影期已合成）——报告页结构化渲染取此，
        // 不再让人读 claimKey/指纹猜语义
        entry.put("kind", claim.kind().name());
        entry.put("statement", claim.reason());
        entry.put("component", claim.scope());
        entry.put("fault_type", claim.evidenceBasis().name());
        // 评分契约槽位只填症状码（告警名）；未声明=诚实空数组，不拿来源标签冒充
        entry.put("symptom_codes", claim.symptomCodes() == null
                ? List.of() : claim.symptomCodes());
        // 来源信息不丢：独立审计键保留（非评分面）
        entry.put("evidence_sources", claim.sources());
        entry.put("evidence_refs", claim.evidenceRefs());
        return entry;
    }

    /** evidence 行 = 断言指纹清单（可回查 rca_claim；非空字符串满足验证链） */
    private static List<String> evidenceLines(ReportAssembler.AssembledReport report) {
        List<String> out = new java.util.ArrayList<>();
        for (ClaimVerdict claim : allClaims(report)) {
            out.add("claim:" + claim.fingerprint());
        }
        return out;
    }

    private static List<ClaimVerdict> allClaims(ReportAssembler.AssembledReport report) {
        List<ClaimVerdict> out = new java.util.ArrayList<>(report.confirmed());
        out.addAll(report.speculative());
        out.addAll(report.unresolved());
        return out;
    }

    /** 确定性 JSON 序列化（失败即编程错误——Map 形态受本类控制） */
    private static final class Json {
        private static String write(Map<String, Object> value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("Native 适配包序列化失败", e);
            }
        }
    }
}
