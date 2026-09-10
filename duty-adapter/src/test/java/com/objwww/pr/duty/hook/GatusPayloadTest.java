package com.objwww.pr.duty.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gatus 事件解析锚（M7-17）：严格 JSON、非严格 body（[RESULT_ERRORS] 带引号截断
 * JSON——gatus-config.yml 头注实测坑）降级正则、状态映射。
 */
class GatusPayloadTest {

    @Test
    @DisplayName("严格 JSON：六字段全取")
    void parsesStrictJson() {
        GatusEvent e = GatusEvent.parse("""
                {"status":"TRIGGERED","endpoint":"RCA_SYSTEM_control_health",
                 "group":"control-plane","target":"http://195:8080/actuator/health",
                 "description":"3 连败","errors":"Get \\"http://195:8080\\": dial refused"}""");
        assertThat(e).isNotNull();
        assertThat(e.status()).isEqualTo("TRIGGERED");
        assertThat(e.endpoint()).isEqualTo("RCA_SYSTEM_control_health");
        assertThat(e.eventStatus()).isEqualTo("firing");
        assertThat(e.errors()).contains("dial refused");
    }

    @Test
    @DisplayName("非严格 body（errors 未转义引号截断 JSON）→ 正则降级仍取 status/endpoint")
    void fallsBackToRegexForNonStrictBody() {
        // Gatus 实测形态：Get "http://…" 内嵌引号使 JSON 在 errors 处断裂
        GatusEvent e = GatusEvent.parse("""
                {"status":"TRIGGERED","endpoint":"RCA_SYSTEM_control_health",
                 "group":"control-plane","target":"http://195:8080/actuator/health",
                 "description":"连接失败",
                 "errors":"Get "http://195:8080/actuator/health": dial tcp refused"}""");
        assertThat(e).isNotNull();
        assertThat(e.status()).isEqualTo("TRIGGERED");
        assertThat(e.endpoint()).isEqualTo("RCA_SYSTEM_control_health");
        assertThat(e.group()).isEqualTo("control-plane");
        // errors 在引号处截断——如实接受（只影响文案，不影响判定）
        assertThat(e.errors()).startsWith("Get");
    }

    @Test
    @DisplayName("RESOLVED→resolved；解析不出 status/endpoint → null")
    void mapsResolvedAndRejectsUndecidable() {
        GatusEvent resolved = GatusEvent.parse(
                "{\"status\":\"RESOLVED\",\"endpoint\":\"e1\"}");
        assertThat(resolved.eventStatus()).isEqualTo("resolved");

        assertThat(GatusEvent.parse("garbage not json")).isNull();
        assertThat(GatusEvent.parse("{\"status\":\"TRIGGERED\"}")).isNull();
        assertThat(GatusEvent.parse("")).isNull();
    }
}
