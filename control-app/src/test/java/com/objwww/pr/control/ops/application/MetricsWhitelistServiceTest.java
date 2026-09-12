package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.application.MetricsWhitelistService.MetricsRangeResponse;
import com.objwww.pr.control.ops.domain.repository.MetricsRangeGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MetricsWhitelistService 单测（方案 §三.12 + 全量联通方案 §5.11/§6 B6；
 * AgentOpsSummaryServiceTest 同模式——假端口）：
 * 白名单键外输入 400 族（IllegalArgumentException）、参数边界（窗幅/step/点数/
 * end&gt;start）、Prometheus 不可达语义（MetricsSourceUnavailableException 透传）、
 * 成功路径 matrix 解析与 asOf 钉 now。
 */
class MetricsWhitelistServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:30:00Z");

    private final FakeGateway gateway = new FakeGateway();
    private final MetricsWhitelistService service = new MetricsWhitelistService(gateway, () -> NOW);

    private static final String MATRIX_OK = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"instance":"host1:9100"},"values":[[1736500000,"12.5"],[1736500060,"NaN"]]},
              {"metric":{},"values":[[1736500000,"3.0"]]}
            ]}}
            """;

    // -------------------------------------------------------------- 白名单

    @Test
    void keyOutsideWhitelistRejected() {
        assertThatThrownBy(() -> service.queryRange("up", "1736500000", "1736503600", "60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
        assertThatThrownBy(() -> service.queryRange(
                "host_cpu_usage or rate(x[5m])", "1736500000", "1736503600", "60"))
                .isInstanceOf(IllegalArgumentException.class);
        // 任意 PromQL 不得触达远端
        assertThat(gateway.calls).isZero();
    }

    @Test
    void whitelistKeyPassesTemplateToGateway() {
        gateway.body = MATRIX_OK;

        MetricsRangeResponse out = service.queryRange(
                "host_cpu_usage", "1736500000", "1736503600", "60");

        assertThat(out.query()).isEqualTo("host_cpu_usage");
        assertThat(out.unit()).isEqualTo("%");
        assertThat(out.asOf()).isEqualTo(NOW);
        assertThat(gateway.lastPromql).contains("node_cpu_seconds_total");
        assertThat(gateway.lastStart).isEqualTo(1736500000L);
        assertThat(gateway.lastEnd).isEqualTo(1736503600L);
        assertThat(gateway.lastStep).isEqualTo(60L);
    }

    // -------------------------------------------------------------- 参数边界

    @Test
    void paramBoundsEnforced() {
        // end <= start
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "1736503600", "1736503600", "60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end 必须大于 start");
        // 窗幅 > 24h
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "1736500000", "1736586401", "60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("窗幅");
        // step 越界（<15s / >3600s / 非数值）
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "1736500000", "1736503600", "14"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step");
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "1736500000", "1736503600", "3601"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step");
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "1736500000", "1736503600", "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        // 点数超限（24h 窗 + step=90s → 961 点 ok；step=60s → 1441 点拒）
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "1736500000", "1736586400", "60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("点数");
        // start/end 非整数 epoch 秒
        assertThatThrownBy(() -> service.queryRange(
                "host_mem_usage", "now-1h", "1736503600", "60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start");
        assertThat(gateway.calls).isZero();
    }

    // -------------------------------------------------------------- 不可达语义

    @Test
    void gatewayUnavailablePropagates() {
        gateway.failure = new MetricsSourceUnavailableException("指标源不可达");

        assertThatThrownBy(() -> service.queryRange(
                "host_disk_usage", "1736500000", "1736503600", "60"))
                .isInstanceOf(MetricsSourceUnavailableException.class);
    }

    @Test
    void malformedRemoteBodyIsUnavailableNotEmpty() {
        gateway.body = "not-json";
        assertThatThrownBy(() -> service.queryRange(
                "host_disk_usage", "1736500000", "1736503600", "60"))
                .isInstanceOf(MetricsSourceUnavailableException.class);

        gateway.body = "{\"status\":\"error\",\"errorType\":\"bad_data\"}";
        assertThatThrownBy(() -> service.queryRange(
                "host_disk_usage", "1736500000", "1736503600", "60"))
                .isInstanceOf(MetricsSourceUnavailableException.class);
    }

    // -------------------------------------------------------------- 解析与诚实空

    @Test
    void matrixParsedWithNaNPointsDropped() {
        gateway.body = MATRIX_OK;

        MetricsRangeResponse out = service.queryRange(
                "host_cpu_usage", "1736500000", "1736503600", "60");

        assertThat(out.series()).hasSize(2);
        assertThat(out.series().get(0).name()).isEqualTo("host1:9100");
        // NaN 点剔除，不伪造值
        assertThat(out.series().get(0).points()).containsExactly(
                new MetricsWhitelistService.MetricPoint(1736500000L, 12.5));
        assertThat(out.series().get(1).name()).isEqualTo("value");
    }

    @Test
    void emptyMatrixIsHonestEmptySeries() {
        gateway.body = """
                {"status":"success","data":{"resultType":"matrix","result":[]}}
                """;

        MetricsRangeResponse out = service.queryRange(
                "run_throughput", "1736500000", "1736503600", "60");

        // 已查询但未采集：空 series 如实返回，不回填模拟数据
        assertThat(out.series()).isEmpty();
        assertThat(gateway.calls).isEqualTo(1);
    }

    private static final class FakeGateway implements MetricsRangeGateway {
        String body;
        RuntimeException failure;
        int calls;
        String lastPromql;
        long lastStart;
        long lastEnd;
        long lastStep;

        @Override
        public String queryRange(String promql, long startEpochSec, long endEpochSec, long stepSec) {
            calls++;
            lastPromql = promql;
            lastStart = startEpochSec;
            lastEnd = endEpochSec;
            lastStep = stepSec;
            if (failure != null) {
                throw failure;
            }
            return body;
        }
    }
}
