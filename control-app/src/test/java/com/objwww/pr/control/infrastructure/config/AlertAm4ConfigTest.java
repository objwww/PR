package com.objwww.pr.control.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AM4 装配纪律行为化（AM4 技术方案 §2：启动期硬失败/运行期 fail-fast 惯例）：
 * fixture 缺失/空 = 启动拒绝（本机无 docker profile 容器，装配类仅测纯逻辑面）。
 */
class AlertAm4ConfigTest {

    @Test
    void fixture存在时读出字节() {
        byte[] bytes = AlertAm4Config.fixtureBytes("am4/fixtures/logs-query.json");

        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("status");
    }

    @Test
    void fixture缺失时启动期硬失败() {
        assertThatThrownBy(() -> AlertAm4Config.fixtureBytes("am4/fixtures/absent.json"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("fixture 读取失败");
    }
}
