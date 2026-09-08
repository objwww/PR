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
 *       fault_type=证据基础、symptom_codes=来源、evidence_refs=证据引用）——不改
 *       FUT-49 两轴语义；</li>
 *   <li>root_cause：确认根因（confirmed 中首个 status=TRUE）→ 三元组；无确认根因 →
 *       诚实 unknown 三元组（unknown/unresolved/NO_CONFIRMED_ROOT_CAUSE），不冒充结论；</li>
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
        pkg.put("summary", "Native 确定性链报告 " + report.outcome()
                + "（confirmed=" + report.confirmed().size()
                + " speculative=" + report.speculative().size()
                + " unresolved=" + report.unresolved().size() + "）");
        pkg.put("root_cause", rootCause(report));
        pkg.put("claims", claims(report));
        pkg.put("evidence", evidenceLines(report));
        pkg.put("impact", "确定性链不产出影响面评估（M6-01 边界），见 claims 分节");
        pkg.put("remediation", "确定性链不生成修复建议，见 claims 证据引用");
        pkg.put("references", List.of());
        return Json.write(pkg);
    }

    /** root_cause 三元组：确认根因优先，缺位诚实 unknown（不臆测） */
    private static Map<String, Object> rootCause(ReportAssembler.AssembledReport report) {
        Optional<ClaimVerdict> root = report.confirmed().stream()
                .filter(c -> c.status() == ClaimStatus.TRUE)
                .findFirst();
        Map<String, Object> rc = new LinkedHashMap<>();
        if (root.isPresent()) {
            ClaimVerdict claim = root.get();
            rc.put("component", claim.scope());
            rc.put("fault_type", claim.claimKey());
            rc.put("reason_code", claim.reason());
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
        entry.put("component", claim.scope());
        entry.put("fault_type", claim.evidenceBasis().name());
        entry.put("symptom_codes", claim.sources());
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
