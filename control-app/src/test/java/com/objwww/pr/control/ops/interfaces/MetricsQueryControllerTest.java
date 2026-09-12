package com.objwww.pr.control.ops.interfaces;

import com.objwww.pr.control.ops.application.MetricsSourceUnavailableException;
import com.objwww.pr.control.ops.application.MetricsWhitelistService;
import com.objwww.pr.control.ops.domain.repository.MetricsRangeGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MetricsQueryController 契约（方案 §三.12 + 全量联通方案 §5.11/§6 B6；
 * EventQueryControllerTest 同模式——standalone MockMvc，RBAC 面上移
 * SecurityFilterChain 由 SecurityConfigTest 链级覆盖）：
 * 200 契约形状 / 白名单外键 400 / 参数越界 400 / 指标源不可达 503
 * {"error":"METRICS_SOURCE_UNAVAILABLE"}（前端「监控数据源未配置/不可达」依据）。
 */
class MetricsQueryControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-11T10:30:00Z");

    private MockMvc mvc;

    /** standalone 装配不挂 Boot 自动配置，jsr310 需显式注册并关时间戳——与生产序列化（ISO 字符串）对齐 */
    private static MockMvc standalone(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @BeforeEach
    void setUp() {
        MetricsRangeGateway gateway = (promql, start, end, step) -> """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"instance":"host1:9100"},"values":[[1736500000,"12.5"]]}
                ]}}
                """;
        mvc = standalone(new MetricsQueryController(new MetricsWhitelistService(gateway, () -> NOW)));
    }

    @Test
    void queryRangeReturnsContractShape() throws Exception {
        mvc.perform(get("/api/metrics/query_range")
                        .param("query", "host_cpu_usage")
                        .param("start", "1736500000")
                        .param("end", "1736503600")
                        .param("step", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("host_cpu_usage"))
                .andExpect(jsonPath("$.unit").value("%"))
                .andExpect(jsonPath("$.series[0].name").value("host1:9100"))
                .andExpect(jsonPath("$.series[0].points[0].epochSec").value(1736500000L))
                .andExpect(jsonPath("$.series[0].points[0].value").value(12.5))
                .andExpect(jsonPath("$.asOf").value(NOW.toString()));
    }

    @Test
    void keyOutsideWhitelistIs400() throws Exception {
        mvc.perform(get("/api/metrics/query_range")
                        .param("query", "node_cpu_seconds_total")
                        .param("start", "1736500000")
                        .param("end", "1736503600")
                        .param("step", "60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("白名单")));
    }

    @Test
    void outOfBoundsParamsAre400() throws Exception {
        mvc.perform(get("/api/metrics/query_range")
                        .param("query", "host_mem_usage")
                        .param("start", "1736500000")
                        .param("end", "1736500000")
                        .param("step", "60"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/metrics/query_range")
                        .param("query", "host_mem_usage")
                        .param("start", "1736500000")
                        .param("end", "1736503600")
                        .param("step", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sourceUnavailableIs503() throws Exception {
        MetricsRangeGateway down = (promql, start, end, step) -> {
            throw new MetricsSourceUnavailableException("指标源不可达");
        };
        MockMvc mvcDown = standalone(new MetricsQueryController(new MetricsWhitelistService(down, () -> NOW)));

        mvcDown.perform(get("/api/metrics/query_range")
                        .param("query", "host_cpu_usage")
                        .param("start", "1736500000")
                        .param("end", "1736503600")
                        .param("step", "60"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("METRICS_SOURCE_UNAVAILABLE"));
    }

    @Test
    void emptyResultIsHonestEmptySeries() throws Exception {
        MetricsRangeGateway empty = (promql, start, end, step) -> """
                {"status":"success","data":{"resultType":"matrix","result":[]}}
                """;
        MockMvc mvcEmpty = standalone(new MetricsQueryController(new MetricsWhitelistService(empty, () -> NOW)));

        mvcEmpty.perform(get("/api/metrics/query_range")
                        .param("query", "run_throughput")
                        .param("start", "1736500000")
                        .param("end", "1736503600")
                        .param("step", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series").isArray())
                .andExpect(jsonPath("$.series").isEmpty());
    }
}
