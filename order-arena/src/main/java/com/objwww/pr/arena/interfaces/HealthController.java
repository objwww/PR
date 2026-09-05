package com.objwww.pr.arena.interfaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康端点（M2-01 验收"容器 health 绿"）：liveness 语义，永远 200 UP——
 * DB 就绪与否不影响 liveness（compose healthcheck 用它；DP-C01 断言 healthy）。
 * 不引 actuator：瘦装配惯例，指标端点见 MetricsController。
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
