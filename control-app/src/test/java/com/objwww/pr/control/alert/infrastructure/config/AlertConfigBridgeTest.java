package com.objwww.pr.control.alert.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G0-03 桥接回归：application.yml 的告警凭证占位符必须能从环境变量解析。
 *
 * <p>背景（BA-10①）：Spring relaxed binding 不会自动把环境变量映射成嵌套属性——
 * yml 显式占位前，{@code @Value} 占位符在 docker profile 启动即解析失败。
 * M6-07：holmes 凭证桥接（app.alert.holmes.*）随第二引擎退场摘除，
 * 本测试的两个 holmes 案同步删除；webhook bearer 桥接与 Hikari 预算钉保留。
 *
 * <p>本测试直接用 StandardEnvironment + application.yml 验证解析链：
 * 环境变量在场→透传值；缺席→解析为空串（可解析，不是 null）——bean 不会因占位符炸掉。
 */
class AlertConfigBridgeTest {

    private StandardEnvironment loadYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addFirst(source);
        }
        return env;
    }

    @Test
    void holmesBridgeKeysAreGoneAfterRetirement() throws Exception {
        // M6-07：桥接面摘除后 holmes 键族不可解析（null 而非空串）——
        // 防止 yml 残留死占位符悄悄复活
        StandardEnvironment env = loadYml();
        assertThat(env.getProperty("app.alert.holmes.api-key")).isNull();
        assertThat(env.getProperty("app.alert.holmes.base-url")).isNull();
        assertThat(env.getProperty("app.alert.fallback.enabled")).isNull();
        assertThat(env.getProperty("app.alert.shadow.holmes.enabled")).isNull();
    }

    @Test
    void webhookBearerBridgesFromCanonicalEnvironmentName() throws Exception {
        StandardEnvironment env = loadYml();
        System.setProperty("ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "stub-bearer");
        try {
            assertThat(env.getProperty("app.alert.webhook.bearer")).isEqualTo("stub-bearer");
        } finally {
            System.clearProperty("ALERTMANAGER_WEBHOOK_BEARER_TOKEN");
        }
    }

    /**
     * G0-09 / BA-12④：docker profile 的 Hikari 显式预算（§15 连接预算 12 + 5s 快速失败）。
     * 配置落在 application-docker.yml（默认 profile 排除 DataSource，主 yml 里是死配置）。
     */
    @Test
    void dockerProfilePinsHikariPoolBudget() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application-docker.yml",
                new ClassPathResource("application-docker.yml"));
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addFirst(source);
        }

        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("12");
        assertThat(env.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
    }
}
