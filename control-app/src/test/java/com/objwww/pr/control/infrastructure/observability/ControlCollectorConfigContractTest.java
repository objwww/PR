package com.objwww.pr.control.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * control 平面遥测 collector 静态门（M5-15④"Collector 配置静态校验脚本/装配测试"）：
 * Docker 不可用面下锁住三支柱配置的关键结构——独立出口/独立凭据（env 注入，凭据零落
 * 文件）、资源上限（容器+进程双面）、风险 trace 保留采样（错误/慢全保 + 健康 10%）、
 * 三管线全覆盖、应用侧采样概率面（低基数纪律由 AlertMetricsLabelAllowlistTest 把守）。
 * 真证据 = E2E-AM5-08（切断出口不改业务状态，INV-AM5-8），部署段执行。
 */
class ControlCollectorConfigContractTest {

    private static final Path COLLECTOR_CONFIG = Path.of(
            "../deploy/otelcol-control/otelcol-config.yml");
    private static final Path COMPOSE = Path.of("../deploy/docker-compose.yml");
    private static final Path APP_YML = Path.of("src/main/resources/application.yml");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String norm(String raw) {
        return raw.toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void collectorConfigPinsResourceCapErrorRetentionAndEnvOnlyCredentials() throws IOException {
        String sql = norm(read(COLLECTOR_CONFIG));

        // 资源上限（进程面）：memory_limiter 站所有管线首位
        assertThat(sql)
                .contains("memory_limiter:")
                .contains("limit_mib: 256")
                // 风险 trace 保留：错误全保 + 慢 trace 全保 + 健康 10% 概率
                .contains("tail_sampling:")
                .contains("status_codes: [error]")
                .contains("threshold_ms: 5000")
                .contains("sampling_percentage: 10")
                // 三支柱管线全覆盖且都过资源上限
                .contains("traces:")
                .contains("processors: [memory_limiter, tail_sampling, batch]")
                .contains("processors: [memory_limiter, batch]")
                // 独立出口/独立凭据：全 env 注入，文件零凭据字面量
                .contains("otlp/upstream:")
                .contains("${env:otel_upstream_endpoint}")
                .contains("authorization: \"${env:otel_upstream_token}\"")
                // 本地拉面：metrics 支柱 Prometheus 抓取
                .contains("prometheus/control:")
                .contains("health_check");
    }

    @Test
    void composeWiresCollectorWithRequiredCredentialsLoopbackAndHardening() throws IOException {
        String compose = norm(read(COMPOSE));

        // 服务在位 + 凭据 :? 必填（静默空值 = 出口匿名裸奔，禁止）+ 资源上限容器面
        assertThat(compose)
                .contains("otelcol-control:")
                .contains("otelcol-control/otelcol-config.yml:/etc/otelcol/otelcol-config.yml:ro")
                .contains("otel_upstream_endpoint: ${otel_upstream_endpoint:?}")
                .contains("otel_upstream_token: ${otel_upstream_token:?}")
                .contains("memory: 384m")
                // 入口仅 loopback（INV-AM0-1）；OTLP 4317/4318 不对宿主发布
                .contains("127.0.0.1:9465:9465")
                .contains("127.0.0.1:13133:13133")
                .contains("read_only: true")
                .contains("cap_drop:")
                // control-app 出口指向 collector（INV-AM5-8：无 depends_on，故障只丢遥测）
                .contains("otel_exporter_otlp_endpoint: ${otel_exporter_otlp_endpoint:-http://otelcol-control:4317}");
        assertThat(compose).doesNotContain("4317:4317").doesNotContain("4318:4318");
    }

    @Test
    void applicationYmlCarriesSamplingProbabilityFace() throws IOException {
        String yml = norm(read(APP_YML));

        assertThat(yml)
                .contains("sampling:")
                .contains("probability: ${app_trace_sampling:0.1}");
    }
}
