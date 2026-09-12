package com.objwww.pr.control.ops.interfaces;

import com.objwww.pr.control.ops.application.MetricsSourceUnavailableException;
import com.objwww.pr.control.ops.application.MetricsWhitelistService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 监控页指标白名单代理只读 API（/api/metrics/**；全 GET 零写面，ROLE_OPERATOR 归
 * SecurityFilterChain 矩阵，与 /api/agent-ops/** 同式）。
 *
 * <ul>
 *   <li>GET /api/metrics/query_range?query=&lt;白名单键&gt;&amp;start=&amp;end=&amp;step=
 *       ——方案 §三.12「主机」区小趋势图数据面（全量联通方案 §5.11/§6 B6）。
 *       query 只允许 {@code MetricsWhitelistService.WhitelistKey} 固定枚举，
 *       严禁透传任意 PromQL（防注入）；start/end 为整数 epoch 秒、step 为整数秒，
 *       边界归服务层（窗幅 ≤24h、step 15s~3600s、点数 ≤1000）。</li>
 * </ul>
 *
 * <p>状态码语义：白名单外键/参数越界 400 {"error": ...}（IncidentQueryController
 * 同式）；Prometheus 未配置/不可达/应答非法 503 {"error": "METRICS_SOURCE_UNAVAILABLE"}
 * ——前端据此显示「监控数据源未配置/不可达」，不以空数据冒充已采集。
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
public class MetricsQueryController {

    private final MetricsWhitelistService service;

    public MetricsQueryController(MetricsWhitelistService service) {
        this.service = service;
    }

    @GetMapping("/query_range")
    public ResponseEntity<?> queryRange(@RequestParam String query,
                                        @RequestParam String start,
                                        @RequestParam String end,
                                        @RequestParam String step) {
        try {
            return ResponseEntity.ok(service.queryRange(query, start, end, step));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (MetricsSourceUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "METRICS_SOURCE_UNAVAILABLE"));
        }
    }
}
