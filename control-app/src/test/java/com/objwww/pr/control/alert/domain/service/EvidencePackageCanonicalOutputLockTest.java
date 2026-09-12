package com.objwww.pr.control.alert.domain.service;

import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 输出形态锁案（BA-22 迁移面）：packageJson 规范化输出由 ObjectNode.toString()
 * （输入键序）改为 canonical 字典序——前置检查确认无下游 digest 对账（raw/payload
 * digest 只对原文），本案锁死新输出形态防再漂移。
 */
class EvidencePackageCanonicalOutputLockTest {

    @Test
    @DisplayName("v1 规范化输出 = canonical 字典序键序（evidence/references 语义内容不变）")
    void v1OutputIsCanonicalKeyOrder() {
        EvidencePackageValidator validator = new EvidencePackageValidator(65536, 20, 400);
        String body = "{\"analysis\":\"{\\\"schema_version\\\":1,\\\"summary\\\":\\\"s\\\","
                + "\\\"root_cause\\\":\\\"r\\\",\\\"evidence\\\":[\\\"e1\\\"],"
                + "\\\"impact\\\":\\\"i\\\",\\\"remediation\\\":\\\"m\\\","
                + "\\\"references\\\":[{\\\"artifact_ref\\\":\\\"prometheus://q\\\"}]}\"}";
        // analysis 键故意乱序（remediation 在 impact 前），canonical 后仍字典序
        String scrambled = body.replace("\\\"impact\\\":\\\"i\\\",\\\"remediation\\\":\\\"m\\\"",
                "\\\"remediation\\\":\\\"m\\\",\\\"impact\\\":\\\"i\\\"");
        String scrambledBody = "{\"analysis\":\"{\\\"schema_version\\\":1,\\\"summary\\\":\\\"s\\\","
                + "\\\"root_cause\\\":\\\"r\\\",\\\"evidence\\\":[\\\"e1\\\"],"
                + "\\\"remediation\\\":\\\"m\\\",\\\"impact\\\":\\\"i\\\","
                + "\\\"references\\\":[{\\\"artifact_ref\\\":\\\"prometheus://q\\\"}]}\"}";

        var fromOrdered = validator.validate(body);
        var fromScrambled = validator.validate(scrambledBody);

        assertThat(fromOrdered.status()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        assertThat(fromScrambled.status()).isEqualTo(ValidationStatus.STRUCTURE_VALIDATED);
        // 键序漂移输入 → 同一 canonical 输出（键序不变性 = digest 稳定面）
        assertThat(fromScrambled.packageJson()).isEqualTo(fromOrdered.packageJson());
        assertThat(fromOrdered.packageJson())
                .isEqualTo("{\"evidence\":[\"e1\"],\"impact\":\"i\","
                        + "\"references\":[{\"artifact_ref\":\"prometheus://q\"}],"
                        + "\"remediation\":\"m\",\"root_cause\":\"r\","
                        + "\"schema_version\":1,\"summary\":\"s\"}");
    }
}
