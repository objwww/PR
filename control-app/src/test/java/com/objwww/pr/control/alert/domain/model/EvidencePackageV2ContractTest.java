package com.objwww.pr.control.alert.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * M3-01 验收：EvidencePackage v2 契约——JSON 正反样本、长度/枚举/schema 单测。
 */
class EvidencePackageV2ContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 合法 v2 包（§6.3 形状） */
    private ObjectNode validPackageNode() {
        ObjectNode pkg = mapper.createObjectNode();
        pkg.put("schema_version", 2);
        pkg.put("summary", "checkout 错误率超阈值");
        ObjectNode rc = pkg.putObject("root_cause");
        rc.put("component", "payment");
        rc.put("fault_type", "business_error_rate");
        rc.put("reason_code", "paymentFailure=50%");
        var claims = pkg.putArray("claims");
        ObjectNode c1 = claims.addObject();
        c1.put("claim_type", "root_cause");
        c1.put("status", "TRUE");
        c1.put("component", "payment");
        c1.put("fault_type", "business_error_rate");
        c1.putArray("symptom_codes").add("PAYMENT_5XX_HIGH");
        c1.putArray("evidence_refs").add("prometheus://query/5xx_rate");
        ObjectNode c2 = claims.addObject();
        c2.put("claim_type", "symptom");
        c2.put("status", "UNKNOWN");
        c2.put("component", "payment");
        c2.put("fault_type", "dependency_unreachable");
        c2.putArray("symptom_codes");
        c2.putArray("evidence_refs");
        pkg.putArray("evidence").add("Prometheus 查询显示 5xx 占比 0.5");
        pkg.put("impact", "支付成功率下降");
        pkg.put("remediation", "关闭故障注入开关");
        pkg.putArray("references").addObject().put("artifact_ref", "prometheus://query/5xx_rate");
        return pkg;
    }

    private EvidencePackageV2 parse(ObjectNode pkg) {
        JsonNode node = pkg;
        return EvidencePackageV2.fromMap(com.objwww.pr.control.alert.application.EvidencePackageJsonCodec.toMap(node));
    }

    // ---------------------------------------------------------------- 正样本

    @Test
    void validPackageParsesToTypedContract() {
        EvidencePackageV2 pkg = parse(validPackageNode());

        assertThat(pkg.schemaVersion()).isEqualTo(2);
        assertThat(pkg.summary()).isEqualTo("checkout 错误率超阈值");
        assertThat(pkg.rootCause().component()).isEqualTo("payment");
        assertThat(pkg.rootCause().faultType()).isEqualTo("business_error_rate");
        assertThat(pkg.rootCause().reasonCode()).isEqualTo("paymentFailure=50%");
        assertThat(pkg.claims()).hasSize(2);
        assertThat(pkg.claims().get(0).status()).isEqualTo(ClaimStatus.TRUE);
        assertThat(pkg.claims().get(0).symptomCodes()).containsExactly("PAYMENT_5XX_HIGH");
        assertThat(pkg.claims().get(1).status()).isEqualTo(ClaimStatus.UNKNOWN);
        assertThat(pkg.claims().get(1).symptomCodes()).isEmpty();
        assertThat(pkg.referenceArtifactRefs()).containsExactly("prometheus://query/5xx_rate");
    }

    @Test
    void emptySymptomAndRefArraysAreLegal() {
        ObjectNode pkg = validPackageNode();
        ((ObjectNode) pkg.get("claims").get(0)).putArray("symptom_codes");
        ((ObjectNode) pkg.get("claims").get(0)).putArray("evidence_refs");

        assertThat(parse(pkg).claims().get(0).symptomCodes()).isEmpty();
    }

    @Test
    void recordRoundTripsThroughConstructor() {
        TypedRootCause rc = new TypedRootCause("payment", "business_error_rate", "flag inject");
        ReportClaim claim = new ReportClaim("root_cause", ClaimStatus.FALSE, "payment",
                "business_error_rate", List.of(), List.of("prometheus://q/1"));
        EvidencePackageV2 pkg = new EvidencePackageV2(2, "s", rc, List.of(claim),
                List.of("e"), "i", "r", List.of());

        assertThat(pkg.claims()).hasSize(1);
        assertThat(pkg.evidence()).containsExactly("e");
    }

    // ---------------------------------------------------------------- 反样本（形状/schema）

    @Test
    @DisplayName("缺键/错类型/错枚举 → IllegalArgumentException(形状映射层)")
    void shapeViolationsAreRejected() {
        // 缺 root_cause 对象
        ObjectNode missingRoot = validPackageNode();
        missingRoot.remove("root_cause");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(missingRoot))
                .withMessageContaining("root_cause");

        // schema_version 非整型
        ObjectNode badVersion = validPackageNode();
        badVersion.put("schema_version", "2");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(badVersion))
                .withMessageContaining("schema_version");

        // claim 缺 status
        ObjectNode missingStatus = validPackageNode();
        ((ObjectNode) missingStatus.get("claims").get(0)).remove("status");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(missingStatus))
                .withMessageContaining("status");

        // status 枚举外（猜不出来的一律拒）
        ObjectNode badStatus = validPackageNode();
        ((ObjectNode) badStatus.get("claims").get(0)).put("status", "MAYBE");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(badStatus))
                .withMessageContaining("MAYBE");

        // evidence 条目非字符串
        ObjectNode badEvidence = validPackageNode();
        badEvidence.putArray("evidence").add(42);
        assertThatIllegalArgumentException().isThrownBy(() -> parse(badEvidence))
                .withMessageContaining("evidence");

        // reference 缺 artifact_ref
        ObjectNode badRef = validPackageNode();
        var refArr = mapper.createArrayNode();
        refArr.addObject().put("url", "https://x");
        badRef.set("references", refArr);
        assertThatIllegalArgumentException().isThrownBy(() -> parse(badRef))
                .withMessageContaining("artifact_ref");

        // 整个包不是对象
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EvidencePackageV2.fromMap(com.objwww.pr.control.alert.application.EvidencePackageJsonCodec.toMap(mapper.getNodeFactory().textNode("x"))));
    }

    // ---------------------------------------------------------------- 反样本（长度/构造器校验）

    @Test
    @DisplayName("typed 字段 blank/null/超长 → 构造器拒绝")
    void typedFieldViolationsAreRejected() {
        assertThatNullPointerException().isThrownBy(() -> new TypedRootCause(null, "t", "r"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TypedRootCause(" ", "t", "r"))
                .withMessageContaining("component");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TypedRootCause("c".repeat(129), "t", "r"))
                .withMessageContaining("component");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TypedRootCause("c", "t".repeat(65), "r"))
                .withMessageContaining("fault_type");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TypedRootCause("c", "t", "r".repeat(129)))
                .withMessageContaining("reason_code");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReportClaim(" ".repeat(65), ClaimStatus.TRUE, "c", "f",
                        List.of(), List.of()))
                .withMessageContaining("claim_type");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReportClaim("t", ClaimStatus.TRUE, "c", "f",
                        List.of("s".repeat(257)), List.of()))
                .withMessageContaining("symptom_codes");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReportClaim("t", ClaimStatus.TRUE, "c", "f",
                        List.of(), java.util.stream.IntStream.range(0, 17)
                                .mapToObj(i -> "r" + i).toList()))
                .withMessageContaining("evidence_refs");
        assertThatNullPointerException()
                .isThrownBy(() -> new ReportClaim("t", null, "c", "f", List.of(), List.of()));
    }

    @Test
    @DisplayName("claims 条数与 schema_version 固定值")
    void packageLevelCapsAreEnforced() {
        TypedRootCause rc = new TypedRootCause("c", "f", "r");
        ReportClaim claim = new ReportClaim("t", ClaimStatus.TRUE, "c", "f", List.of(), List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EvidencePackageV2(2, "s", rc,
                        java.util.stream.IntStream.range(0, 33).mapToObj(i -> claim).toList(),
                        List.of(), "i", "r", List.of()))
                .withMessageContaining("claims");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EvidencePackageV2(1, "s", rc, List.of(),
                        List.of(), "i", "r", List.of()))
                .withMessageContaining("schema_version");
    }
}
