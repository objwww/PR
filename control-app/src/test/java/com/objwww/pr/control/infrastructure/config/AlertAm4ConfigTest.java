package com.objwww.pr.control.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AM4 装配纪律行为化（AM4 技术方案 §2：启动期硬失败/运行期 fail-fast 惯例）：
 * fixture 缺失/空 = 启动拒绝（本机无 docker profile 容器，装配类仅测纯逻辑面）；
 * fixture 响应契约 = {@code status=success + data.result 非空序列}
 * （SingleToolEvidenceAgent 统一解析面——形状违约即 E2E 静默 NO_DATA）。
 */
class AlertAm4ConfigTest {

    @Test
    void fixture存在时读出字节() {
        byte[] bytes = AlertAm4Config.fixtureBytes("am4/fixtures/logs-query.json");

        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("status");
    }

    @Test
    void logsFixture响应契约dataResult非空序列() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        Map<?, ?> payload = mapper.readValue(AlertAm4Config
                .fixtureBytes("am4/fixtures/logs-query.json"), Map.class);

        assertThat(payload.get("status")).isEqualTo("success");
        assertThat(dataResult(payload)).isNotEmpty();
    }

    @Test
    void changeFixture响应契约dataResult非空序列() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        Map<?, ?> payload = mapper.readValue(AlertAm4Config
                .fixtureBytes("am4/fixtures/change-query.json"), Map.class);

        assertThat(payload.get("status")).isEqualTo("success");
        assertThat(dataResult(payload)).isNotEmpty();
    }

    @Test
    void fixture缺失时启动期硬失败() {
        assertThatThrownBy(() -> AlertAm4Config.fixtureBytes("am4/fixtures/absent.json"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("fixture 读取失败");
    }

    /** 与 SingleToolEvidenceAgent.dataSeries 同构的最小解析（防契约漂移） */
    private static List<?> dataResult(Map<?, ?> payload) {
        Object data = payload.get("data");
        if (!(data instanceof Map)) {
            return List.of();
        }
        Object result = ((Map<?, ?>) data).get("result");
        return result instanceof List<?> series ? series : List.of();
    }
}
