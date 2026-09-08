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
 * NativeReportAdapter UT（M6-01 落点 6）：AssembledReport 三态（CONFIRMED/PARTIAL/
 * UNRESOLVED）→ v2 证据包适配——确定性映射（无 LLM 输入口）：裁决状态机分节照抄进
 * claims[]，确认根因进 root_cause，无确认根因 = 诚实 unknown 三元组；外层套
 * engine=NATIVE 标记（随脱敏原文落档，规范化包保持 schema 纯度）。适配包必须过
 * {@link EvidencePackageValidator} 结构验证链（STRUCTURE_VALIDATED）。
 */
class NativeReportAdapterTest {

    private static final String SNAPSHOT = "ab".repeat(32);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EvidencePackageValidator validator =
            new EvidencePackageValidator(65_536, 32, 4_096);

    @Test
    @DisplayName("CONFIRMED：双源 TRUE 进 root_cause，claims 全量列明，外层带 engine=NATIVE")
    void confirmedMapsConfirmedRootCause() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.CONFIRMED, SNAPSHOT,
                List.of(verdict("cpu_saturation", ClaimStatus.TRUE,
                        EvidenceBasis.MULTI_SOURCE_CONSISTENT, "svc-a", "cpu over 95%")),
                List.of(), List.of(), 0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);

        assertThat(adapted.outerJson()).contains("\"engine\":\"NATIVE\"");
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(result.schemaVersion()).isEqualTo(EvidencePackageV2.SCHEMA_VERSION);

        JsonNode pkg = readInner(result.packageJson());
        assertThat(pkg.get("root_cause").get("component").asText()).isEqualTo("svc-a");
        assertThat(pkg.get("root_cause").get("fault_type").asText()).isEqualTo("cpu_saturation");
        assertThat(pkg.get("root_cause").get("reason_code").asText()).isEqualTo("cpu over 95%");
        assertThat(pkg.get("claims")).hasSize(1);
        assertThat(pkg.get("claims").get(0).get("status").asText()).isEqualTo("TRUE");
        assertThat(pkg.get("claims").get(0).get("symptom_codes")).hasSize(2);
        assertThat(result.typedPackage()).isNotNull();
        assertThat(result.redactedRawText()).contains("NATIVE");
    }

    @Test
    @DisplayName("PARTIAL：有确认根因 + 推测/未决残留照实列 claims（不臆测收敛）")
    void partialKeepsResidueClaims() {
        ReportAssembler.AssembledReport report = new ReportAssembler.AssembledReport(
                ReportAssembler.Outcome.PARTIAL, SNAPSHOT,
                List.of(verdict("cpu_saturation", ClaimStatus.TRUE,
                        EvidenceBasis.MULTI_SOURCE_CONSISTENT, "svc-a", "cpu over 95%")),
                List.of(verdict("config_change", ClaimStatus.TRUE,
                        EvidenceBasis.SINGLE_SOURCE, "svc-b", "deploy window")),
                List.of(verdict("gc_pressure", ClaimStatus.UNKNOWN,
                        EvidenceBasis.MULTI_SOURCE_CONFLICT, "svc-a", "conflicting")),
                0);

        NativeReportAdapter.Adapted adapted = NativeReportAdapter.adapt(report);
        EvidencePackageValidator.Result result = validator.validate(adapted.outerJson());
        assertThat(result.status()).isEqualTo(com.objwww.pr.control.alert.domain.model.ValidationStatus.STRUCTURE_VALIDATED);

        JsonNode pkg = readInner(result.packageJson());
        assertThat(pkg.get("root_cause").get("fault_type").asText()).isEqualTo("cpu_saturation");
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
    }

    // ------------------------------------------------------------------ 夹具

    private static ClaimVerdict verdict(String key, ClaimStatus status, EvidenceBasis basis,
            String scope, String reason) {
        return new ClaimVerdict(key, scope, "07:50/08:00", 7L, SNAPSHOT, status, basis,
                List.of("prometheus", "logs"), reason,
                List.of("e1", "e2"), "m6-policy");
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
