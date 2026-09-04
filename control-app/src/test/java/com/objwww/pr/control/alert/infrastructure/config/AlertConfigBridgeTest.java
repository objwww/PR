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
 * <p>背景（BA-10①）：Spring relaxed binding 不会自动把 {@code HOLMES_API_KEY} 环境变量
 * 映射成 {@code app.alert.holmes.api-key} 属性——yml 显式占位前，
 * {@code AlertFlowConfig} 的 {@code @Value("${app.alert.holmes.api-key}")}（无默认值）
 * 在 docker profile 启动即 placeholder 解析失败。
 *
 * <p>本测试直接用 StandardEnvironment + application.yml 验证解析链：
 * 环境变量在场→透传值；缺席→解析为空串（可解析，不是 null）——bean 不会因占位符炸掉，
 * 凭证缺失由启动自检（AlertSelfCheck）按 holmesEnabled 语义拦截。
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
    void holmesApiKeyBridgesFromEnvironmentVariable() throws Exception {
        StandardEnvironment env = loadYml();
        System.setProperty("HOLMES_API_KEY", "stub-holmes-key");
        try {
            assertThat(env.getProperty("app.alert.holmes.api-key"))
                    .isEqualTo("stub-holmes-key");
        } finally {
            System.clearProperty("HOLMES_API_KEY");
        }
    }

    @Test
    void holmesApiKeyResolvesToEmptyWhenEnvironmentAbsent() throws Exception {
        StandardEnvironment env = loadYml();
        System.clearProperty("HOLMES_API_KEY");
        // 可解析为空串（占位符失败与空值是两回事；后者交给自检拦截）
        assertThat(env.getProperty("app.alert.holmes.api-key")).isEqualTo("");
        // base-url 带容器内网默认值
        assertThat(env.getProperty("app.alert.holmes.base-url"))
                .isEqualTo("http://holmes:8080");
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
