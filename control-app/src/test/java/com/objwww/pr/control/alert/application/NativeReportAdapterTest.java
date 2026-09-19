package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.claim.ReportAssembler;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.service.EvidencePackageValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NativeReportAdapter UT（M6-01 落点 6 + 根因评分贯通修复）：AssembledReport 三态
 * （CONFIRMED/PARTIAL/UNRESOLVED）→ v2 证据包适配——确定性映射（无 LLM 输入口）：
 * 裁决状态机分节照抄进 claims[]；root_cause = 确认根因携带的结构化三元组直填
 * （canonical 码，评分器词表等值命中面），确认根因未携带三元组或无确认根因 =
 * 诚实 unknown 三元组——不拿 scope/claimKey 冒充（旧实现的结构性 miss 根因）。
 * 外层套 engine=NATIVE 标记（随脱敏原文落档，规范化包保持 schema 纯度）。适配包
 * 必须过 {@link EvidencePackageValidator} 结构验证链（STRUCTURE_VALIDATED）。
 */
class NativeReportAdapterTest {

    private static final String SNAPSHOT = "ab".repeat(32);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final com.objwww.pr.control.alert.domain.model.TypedRootCause S1_TRIPLE =
            new com.objwww.pr.control.alert.domain.model.TypedRootCause(
                    "payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");
    private final EvidencePackageValidator validator =
            new EvidencePackageValidator(65_536, 32, 4_096);

    @Test
    @DisplayName("CONFIRMED：确认根因携带三元组 → root_cause 直填 canonical 码，外层带 engine=NATIVE")
    void confirmedMapsStructuredRootCause() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT,
                List.of(typedVerdict("c1", ClaimStatus.TRUE,
                        EvidenceBasis.MULTI_SOURCE_CONSISTENT, "primary",
                        "payment 扣款按比例失败", S1_TRIPLE)),
                List.of(), List.of(), 0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);

        assertThat(adapted.outerJson()).contains("\"engine\":\"NATIVE\"");
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(result.schemaVersion()).isEqualTo(EvidencePackageV2.SCHEMA_VERSION);

        JsonNode pkg = readInner(result.packageJson());
        assertThat(pkg.get("root_cause").get("component").asText()).isEqualTo("payment");
        assertThat(pkg.get("root_cause").get("fault_type").asText())
                .isEqualTo("BUSINESS_ERROR_RATE");
        assertThat(pkg.get("root_cause").get("reason_code").asText())
                .isEqualTo("PAYMENT_CHARGE_FAILURE");
        assertThat(pkg.get("claims")).hasSize(1);
        assertThat(pkg.get("claims").get(0).get("status").asText()).isEqualTo("TRUE");
        // 人读面：断言类型 + 原文陈述随包透出（报告页结构化渲染取此）
        assertThat(pkg.get("claims").get(0).get("kind").asText()).isEqualTo("ROOT_CAUSE");
        assertThat(pkg.get("claims").get(0).get("statement").asText())
                .isEqualTo("payment 扣款按比例失败");
        // 六要素摘要：根因中文自然陈述（词典翻译，裸英文码不进正文）+ 置信度 + 依据
        assertThat(pkg.get("summary").asText())
                .contains("确认根因：支付服务发生「业务错误率升高」")
                .doesNotContain("BUSINESS_ERROR_RATE")
                .doesNotContain("PAYMENT_CHARGE_FAILURE")
                .contains("来自 日志、指标")
                .contains("结论置信度：高");
        // 未声明症状码 → 诚实空数组（不再拿来源标签冒充，BA-158 同族修复）
        assertThat(pkg.get("claims").get(0).get("symptom_codes")).isEmpty();
        // 来源信息不丢：独立审计键保留
        assertThat(pkg.get("claims").get(0).get("evidence_sources")).hasSize(2);
        assertThat(result.typedPackage()).isNotNull();
        assertThat(result.redactedRawText()).contains("NATIVE");
    }

    @Test
    @DisplayName("SYMPTOM claim 携带症状码 → claims[].symptom_codes 直填告警名（评分契约槽位）")
    void symptomClaimMapsDeclaredCodes() {
        ClaimVerdict symptom = new ClaimVerdict("c1", "primary", "07:50/08:00", 7L,
                SNAPSHOT, ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("prometheus", "logs"), "ArenaDuplicateOrders firing",
                List.of("e1", "e2"), "m6-policy",
                com.objwww.pr.control.alert.domain.claim.ClaimKind.SYMPTOM,
                null, List.of("ArenaDuplicateOrders"));
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.PARTIAL, SNAPSHOT,
                List.of(), List.of(symptom), List.of(), 0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);

        JsonNode pkg = readInner(result.packageJson());
        JsonNode entry = pkg.get("claims").get(0);
        assertThat(entry.get("symptom_codes")).hasSize(1);
        assertThat(entry.get("symptom_codes").get(0).asText())
                .isEqualTo("ArenaDuplicateOrders");
        assertThat(result.typedPackage().claims().get(0).symptomCodes())
                .containsExactly("ArenaDuplicateOrders");
        // 来源标签不冒充症状码，独立审计键保留
        assertThat(entry.get("evidence_sources")).hasSize(2);
    }

    @Test
    @DisplayName("CONFIRMED 但确认根因未携带三元组 → 诚实 unknown（不拿 scope/claimKey 冒充）")
    void confirmedWithoutTripleIsHonestUnknown() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT,
                List.of(verdict("c1", ClaimStatus.TRUE,
                        EvidenceBasis.MULTI_SOURCE_CONSISTENT, "primary", "cpu over 95%")),
                List.of(), List.of(), 0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);

        JsonNode pkg = readInner(result.packageJson());
        assertThat(pkg.get("root_cause").get("component").asText()).isEqualTo("unknown");
        assertThat(pkg.get("root_cause").get("fault_type").asText()).isEqualTo("unresolved");
        assertThat(pkg.get("root_cause").get("reason_code").asText())
                .isEqualTo("NO_CONFIRMED_ROOT_CAUSE");
    }

    @Test
    @DisplayName("PARTIAL：有确认根因 + 推测/未决残留照实列 claims（不臆测收敛）")
    void partialKeepsResidueClaims() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.PARTIAL, SNAPSHOT,
                List.of(typedVerdict("c1", ClaimStatus.TRUE,
                        EvidenceBasis.MULTI_SOURCE_CONSISTENT, "primary",
                        "payment 扣款按比例失败", S1_TRIPLE)),
                List.of(verdict("config_change", ClaimStatus.TRUE,
                        EvidenceBasis.SINGLE_SOURCE, "svc-b", "deploy window")),
                List.of(verdict("gc_pressure", ClaimStatus.UNKNOWN,
                        EvidenceBasis.MULTI_SOURCE_CONFLICT, "svc-a", "conflicting")),
                0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);

        JsonNode pkg = readInner(result.packageJson());
        assertThat(pkg.get("root_cause").get("fault_type").asText())
                .isEqualTo("BUSINESS_ERROR_RATE");
        assertThat(pkg.get("claims")).hasSize(3);
    }

    @Test
    @DisplayName("UNRESOLVED：诚实 unknown 三元组，零确认根因（不冒充结论）")
    void unresolvedIsHonestUnknown() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.UNRESOLVED, SNAPSHOT,
                List.of(), List.of(),
                List.of(verdict("gc_pressure", ClaimStatus.UNKNOWN,
                        EvidenceBasis.MULTI_SOURCE_CONFLICT, "svc-a", "conflicting")),
                0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);

        JsonNode pkg = readInner(result.packageJson());
        assertThat(pkg.get("root_cause").get("component").asText()).isEqualTo("unknown");
        assertThat(pkg.get("root_cause").get("fault_type").asText()).isEqualTo("unresolved");
        assertThat(pkg.get("root_cause").get("reason_code").asText())
                .isEqualTo("NO_CONFIRMED_ROOT_CAUSE");
        // UNRESOLVED 摘要如实写"未确认根因"+ 未定论置信度（不编造结论）
        assertThat(pkg.get("summary").asText())
                .contains("未确认根因").contains("未定论");
    }

    @Test
    @DisplayName("六要素摘要自然化：告警中文名+中文根因+量化影响+通用处置建议；引用尾注与裸英文码不进正文")
    void summaryIsNaturalSixElementsChinese() {
        // 投影期 reason 形态：模型陈述 + 准入注记 + {ref:ROLE} 判定留痕（审计面）
        ClaimVerdict root = new ClaimVerdict("c2", "primary", "07:50/08:00", 7L,
                SNAPSHOT, ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("prometheus", "logs"),
                "订单服务幂等校验被跳过，同一意图重复创单，重复订单计数升至 2 "
                        + "[引用已准入] {43db83fb-0000-0000-0000-000000000001:SUPPORTS,"
                        + "43db83fb-0000-0000-0000-000000000002:SUPPORTS}",
                List.of("43db83fb-0000-0000-0000-000000000001",
                        "43db83fb-0000-0000-0000-000000000002"),
                "m6-policy",
                com.objwww.pr.control.alert.domain.claim.ClaimKind.ROOT_CAUSE,
                new com.objwww.pr.control.alert.domain.model.TypedRootCause(
                        "order-arena", "IDEMPOTENCY_BYPASS", "DUPLICATE_CREATE_SAME_INTENT"),
                List.of("ArenaDuplicateOrders"));
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT, List.of(root),
                List.of(), List.of(), 0);

        JsonNode pkg = readInner(validator.validate(NativeReportAdapter.adapt(report)
                .outerJson()).packageJson());
        String summary = pkg.get("summary").asText();
        // 六要素：发生了什么（告警中文名，不裸显 alertname）
        assertThat(summary).contains("告警「订单重复创建」")
                .doesNotContain("ArenaDuplicateOrders");
        // 根因是什么：组件/故障类型中文自然陈述 + 一句话解释括注
        assertThat(summary).contains("确认根因：订单服务发生「幂等失效」")
                .doesNotContain("IDEMPOTENCY_BYPASS")
                .doesNotContain("order-arena");
        // 凭什么判断：引用数 + 中文来源名 + 互证基础
        assertThat(summary).contains("判断依据：2 条证据引用，来自 日志、指标，多源互证一致");
        // 影响多大：量化事实从陈述提取
        assertThat(summary).contains("影响面：订单服务幂等校验被跳过，同一意图重复创单，重复订单计数升至 2");
        // 有多大把握 + 建议怎么办（按故障类型的通用处置方向，标注口径）
        assertThat(summary).contains("结论置信度：高")
                .contains("处置建议：拦截重复流量")
                .contains("通用处置方向");
        // 引用尾注/uuid 不进正文（只活在 evidence_refs 字段）
        assertThat(summary).doesNotContain("{").doesNotContain("SUPPORTS")
                .doesNotContain("43db83fb");
        // impact/remediation 分节同口径填充
        assertThat(pkg.get("impact").asText()).contains("重复订单计数升至 2");
        assertThat(pkg.get("remediation").asText())
                .contains("通用处置方向").contains("拦截重复流量");
    }

    @Test
    @DisplayName("词典未命中 fault_type：回退原文码 + 处置建议诚实句（不猜测翻译）")
    void unknownFaultTypeFallsBackHonestly() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT,
                List.of(typedVerdict("c1", ClaimStatus.TRUE,
                        EvidenceBasis.SINGLE_SOURCE, "primary", "磁盘写满导致写入失败",
                        new com.objwww.pr.control.alert.domain.model.TypedRootCause(
                                "svc-x", "DISK_FULL", "DISK_FULL_NO_SPACE"))),
                List.of(), List.of(), 0);

        JsonNode pkg = readInner(validator.validate(NativeReportAdapter.adapt(report)
                .outerJson()).packageJson());
        assertThat(pkg.get("summary").asText())
                .contains("确认根因：svc-x发生「DISK_FULL」") // 未命中回退原文
                .contains("仅单一来源")
                .contains("该故障类型暂无预置处置方向")
                .contains("影响面：未量化，见下方证据明细"); // 陈述无量化事实
        assertThat(pkg.get("remediation").asText()).contains("未登记通用处置方向");
    }

    @Test
    @DisplayName("清洗函数：{ref:ROLE} 尾注剥除 + 影响提取只挑含数字分句")
    void stripAndImpactHelpers() {
        assertThat(NativeReportAdapter.stripCitationFace(
                "陈述内容 [注记] {e1:SUPPORTS,e2:CONTEXT}"))
                .isEqualTo("陈述内容 [注记]");
        assertThat(NativeReportAdapter.stripCitationFace("纯陈述")).isEqualTo("纯陈述");
        assertThat(NativeReportAdapter.stripCitationFace(null)).isEmpty();
        assertThat(NativeReportAdapter.impactFact("无数字陈述句")).isEqualTo("未量化，见下方证据明细");
        assertThat(NativeReportAdapter.impactFact("错误率升至 50%。另有说明"))
                .isEqualTo("错误率升至 50%");
    }

    // ------------------------------------------------------------------ 夹具
    private static ClaimVerdict verdict(String key, ClaimStatus status, EvidenceBasis basis,
            String scope, String reason) {
        return new ClaimVerdict(key, scope, "07:50/08:00", 7L, SNAPSHOT, status, basis,
                List.of("prometheus", "logs"), reason,
                List.of("e1", "e2"), "m6-policy");
    }

    /** 携带结构化根因三元组的确认断言（V147 评分贯通面） */
    private static ClaimVerdict typedVerdict(String key, ClaimStatus status,
            EvidenceBasis basis, String scope, String reason,
            com.objwww.pr.control.alert.domain.model.TypedRootCause triple) {
        return new ClaimVerdict(key, scope, "07:50/08:00", 7L, SNAPSHOT, status, basis,
                List.of("prometheus", "logs"), reason,
                List.of("e1", "e2"), "m6-policy",
                com.objwww.pr.control.alert.domain.claim.ClaimKind.ROOT_CAUSE, triple);
    }

    /** validator 产出的规范化包 = 内层 v2 JSON 串，直接解析便于断言 */
    private JsonNode readInner(String packageJson) {
        try {
            return MAPPER.readTree(packageJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
