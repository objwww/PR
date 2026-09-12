package com.objwww.pr.control.ops.interfaces;

import com.objwww.pr.control.ops.application.AgentOpsSummaryService;
import com.objwww.pr.control.ops.application.AgentOpsSummaryService.AgentOpsSummaryResponse;
import com.objwww.pr.control.ops.application.AgentOpsSummaryService.WorkerActivityResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * UI-6 监控大盘只读投影 API（/api/agent-ops/**；全 GET 零写面，ROLE_OPERATOR 归
 * SecurityFilterChain 矩阵，与 /api/v1/** 同式）。
 *
 * <ul>
 *   <li>GET /api/agent-ops/summary——大盘聚合（runs/cases/outbox/LLM 账本 24h 窗 +
 *       TopTools；口径见 AgentOpsReader 端口注释，诚实 null 不回填 0）。</li>
 *   <li>GET /api/agent-ops/workers——执行器活性投影（监控页「执行器」区，方案 §三.12）：
 *       近 windowMinutes（默认 60，clamp 5~1440）内有租约活动的 worker 清单，
 *       按租约活动推导，非心跳注册表；asOf 必带。</li>
 * </ul>
 */
@RestController
@Profile("docker")
@RequestMapping(path = "/api/agent-ops", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentOpsController {

    private final AgentOpsSummaryService service;

    public AgentOpsController(AgentOpsSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public AgentOpsSummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/workers")
    public WorkerActivityResponse workers(
            @RequestParam(required = false, defaultValue = "60") int windowMinutes) {
        return service.workers(Duration.ofMinutes(Math.clamp(windowMinutes, 5, 1440)));
    }
}
