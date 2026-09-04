package com.objwww.pr.control.alert.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-A09：EvidencePackageValidator——合法 / 缺字段 / 超尺寸 / schema 版本不符 / 凭证字段（§6.5 链路）。
 */
class EvidencePackageValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidencePackageValidator validator =
            new EvidencePackageValidator(64 * 1024, 1, 10, 2000);

    /** 组装 Holmes /api/chat 响应（官方 ChatResponse）：{analysis: "<analysis JSON 字符串>"} */
    private String holmesResponse(String analysisJson) throws Exception {
        ObjectNode outer = mapper.createObjectNode();
        outer.put("analysis", analysisJson);
        return mapper.writeValueAsString(outer);
    }

    private String validPackage() {
        ObjectNode pkg = mapper.createObjectNode();
        pkg.put("schema_version", 1);
        pkg.put("summary", "checkout 错误率超阈值");
        pkg.put("root_cause", "flagd 注入 paymentFailure=50%");
        pkg.put("impact", "支付成功率下降");
        pkg.put("remediation", "关闭故障注入开关");
        ArrayNode evidence = pkg.putArray("evidence");
        evidence.add("Prometheus 查询显示 5xx 占比 0.5");
        pkg.putArray("references").addObject().put("artifact_ref", "prometheus://query/5xx_rate");
        return pkg.toString();
    }

    @Test
    void validPackagePassesStructureValidation() throws Exception {
        var result = validator.validate(holmesResponse(validPackage()));

        assertThat(result.status()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(result.errors()).isEmpty();
        assertThat(result.packageJson()).contains("\"summary\"").contains("\"root_cause\"");
        // 无敏感内容的原文原样保留
        assertThat(result.redactedRawText()).doesNotContain("****");
    }

    @Test
    void missingFieldIsSchemaMismatch() throws Exception {
        String pkg = validPackage().replace("\"summary\":\"checkout 错误率超阈值\",", "");
        var result = validator.validate(holmesResponse(pkg));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_SCHEMA_MISMATCH);
        assertThat(result.errors()).anyMatch(e -> e.contains("summary"));
    }

    @Test
    void wrongSchemaVersionIsRejected() throws Exception {
        String pkg = validPackage().replace("\"schema_version\":1", "\"schema_version\":2");
        var result = validator.validate(holmesResponse(pkg));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_SCHEMA_VERSION);
    }

    @Test
    void oversizeResponseIsRejected() throws Exception {
        EvidencePackageValidator tight = new EvidencePackageValidator(100, 1, 10, 2000);
        var result = tight.validate(holmesResponse(validPackage()));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_OVERSIZE);
    }

    @Test
    void malformedAnalysisIsRejected() throws Exception {
        assertThat(validator.validate(holmesResponse("not-json")).status())
                .isEqualTo(ValidationStatus.REJECTED_MALFORMED);

        // 外层缺 analysis
        assertThat(validator.validate("{\"conversation_id\":\"c-1\"}").status())
                .isEqualTo(ValidationStatus.REJECTED_MALFORMED);

        assertThat(validator.validate("").status()).isEqualTo(ValidationStatus.REJECTED_MALFORMED);
    }

    @Test
    @DisplayName("BA-14:json 围栏包裹的合法包被提取并通过(DashScope 忽略 response_format 的实测形态)")
    void fencedPackageIsExtractedAndValidated() throws Exception {
        String fenced = "```json\n" + validPackage() + "\n```";
        var result = validator.validate(holmesResponse(fenced));

        assertThat(result.status()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(result.packageJson()).contains("\"root_cause\"");
    }

    @Test
    @DisplayName("BA-14:围栏外的散文前后缀不参与验证,提取后照走完整链(坏包照样拒)")
    void fencedBadPackageStillRejected() throws Exception {
        String fenced = "结论如下:\n```json\n" + validPackage().replace("\"schema_version\":1", "\"schema_version\":2")
                + "\n```\n以上。";
        var result = validator.validate(holmesResponse(fenced));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_SCHEMA_VERSION);
    }

    @Test
    @DisplayName("BA-14:无围栏但有前后缀散文时,按首尾花括号有界提取")
    void proseWrappedPackageIsExtracted() throws Exception {
        String prose = "调查完成。证据包如下:\n" + validPackage() + "\n如需更多信息请联系值班。";
        var result = validator.validate(holmesResponse(prose));

        assertThat(result.status()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
    }

    @Test
    @DisplayName("BA-14:散文里连花括号都没有 → 维持 REJECTED_MALFORMED,提取不放宽标准")
    void pureProseWithoutBracesStillMalformed() throws Exception {
        var result = validator.validate(holmesResponse("The kubectl command is not available, blocking investigation."));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_MALFORMED);
        assertThat(result.errors()).anyMatch(e -> e.contains("JSON 解析失败"));
    }

    @Test
    void credentialLikeArtifactRefIsRejected() throws Exception {
        ObjectNode pkg = (ObjectNode) mapper.readTree(validPackage());
        ((ArrayNode) pkg.get("references")).addObject().put("artifact_ref", "https://evil.example/x");
        var result = validator.validate(holmesResponse(pkg.toString()));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_SCHEMA_MISMATCH);
        assertThat(result.errors()).anyMatch(e -> e.contains("artifact_ref"));
    }

    @Test
    void evidenceOverflowAndFieldTooLongAreRejected() throws Exception {
        EvidencePackageValidator tight = new EvidencePackageValidator(64 * 1024, 1, 2, 10);

        ObjectNode pkg = (ObjectNode) mapper.readTree(validPackage());
        ArrayNode evidence = (ArrayNode) pkg.get("evidence");
        evidence.add("第二条"); evidence.add("第三条");
        var result = tight.validate(holmesResponse(pkg.toString()));

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED_SCHEMA_MISMATCH);
        assertThat(result.errors()).anyMatch(e -> e.contains("evidence"));
    }

    @Test
    void secretsAreRedactedFromRawTextBeforePersistence() {
        // INV-AM1-8 / EX-A13：密钥与敏感字符脱敏后才入库
        String raw = "key=sk-AbCdEf1234567890 auth=Bearer eyJhbGciOi.abc hash=deadbeefdeadbeefdeadbeefdeadbeef";
        String out = validator.redact(raw);

        assertThat(out).doesNotContain("sk-AbCdEf1234567890")
                .doesNotContain("eyJhbGciOi.abc")
                .doesNotContain("deadbeefdeadbeefdeadbeefdeadbeef")
                .contains("****");
    }
}
