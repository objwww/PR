package com.objwww.pr.control.alert.domain.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DecisionProvenance canonical JSON 单测（PA-A5）：null 省略、固定键序（审计比对面）、
 * 控制字符转义、policyVersion 必填、with* 派生不丢字段。
 */
class DecisionProvenanceTest {

    @Test
    void utP01_null省略_固定键序() {
        DecisionProvenance provenance = DecisionProvenance.empty("pa-prod-v1")
                .withAgentBuildSha("sha-9484");
        assertThat(provenance.toCanonicalJson())
                .isEqualTo("{\"agent_build_sha\":\"sha-9484\",\"policy_version\":\"pa-prod-v1\"}");
    }

    @Test
    void utP02_全字段键序固定() {
        DecisionProvenance provenance = new DecisionProvenance("sha-1", "pv-1", "prompt-1",
                "provider-1", "model-1", "tool-1", "1.0.0", "schema-hash-1");
        assertThat(provenance.toCanonicalJson()).isEqualTo(
                "{\"agent_build_sha\":\"sha-1\",\"policy_version\":\"pv-1\","
                        + "\"prompt_version\":\"prompt-1\",\"model_provider\":\"provider-1\","
                        + "\"model_id\":\"model-1\",\"tool_name\":\"tool-1\","
                        + "\"tool_version\":\"1.0.0\",\"tool_schema_hash\":\"schema-hash-1\"}");
    }

    @Test
    void utP03_控制字符与引号转义() {
        DecisionProvenance provenance = DecisionProvenance.empty("pv")
                .withPrompt("a\"b\\c\nd\te\u0001f");
        assertThat(provenance.toCanonicalJson())
                .isEqualTo("{\"policy_version\":\"pv\",\"prompt_version\":"
                        + "\"a\\\"b\\\\c\\nd\\te\\u0001f\"}");
    }

    @Test
    void utP04_policyVersion必填() {
        assertThatThrownBy(() -> new DecisionProvenance("sha", null, null, null, null,
                null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void utP05_withTool派生不丢build锚() {
        DecisionProvenance provenance = DecisionProvenance.empty("pa-prod-v1")
                .withAgentBuildSha("sha-1")
                .withTool("catalog.query", "1.0.0", "schema-hash-1");
        assertThat(provenance.agentBuildSha()).isEqualTo("sha-1");
        assertThat(provenance.policyVersion()).isEqualTo("pa-prod-v1");
        assertThat(provenance.toolName()).isEqualTo("catalog.query");
        assertThat(provenance.toolVersion()).isEqualTo("1.0.0");
        assertThat(provenance.toolSchemaHash()).isEqualTo("schema-hash-1");
        assertThat(provenance.promptVersion()).isNull();
    }
}
