package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.mutation.OperationPlanner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * mutation shadow 操作面（PC-C2）：Approval Shadow 演示/验收驱动入口——
 * 从已解析意图走真锚消费模板（grant→配额→single-use→PREPARED→outbox）。
 * Runner 恒 dry-run（§5 Phase C 铁律：approved → shadow Runner 仍不执行）。
 * 归 operator 角色（浏览器会话与 machine:operator-line 同权，SecurityConfig）。
 */
@RestController
@RequestMapping("/api/mutation")
public class MutationOpsController {

    private final org.springframework.beans.factory.ObjectProvider<OperationPlanner> planner;

    public MutationOpsController(
            org.springframework.beans.factory.ObjectProvider<OperationPlanner> planner) {
        this.planner = Objects.requireNonNull(planner);
    }

    public record PlanRequest(UUID intentId) {
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody PlanRequest request) {
        Objects.requireNonNull(request.intentId(), "intentId 必填");
        OperationPlanner operationPlanner = planner.getIfAvailable();
        if (operationPlanner == null) {
            // 默认（非 docker）profile 无 mutation 装配面——能力缺失显式可见
            return Map.of("status", "UNAVAILABLE", "reason", "MUTATION_FACE_NOT_ASSEMBLED");
        }
        OperationPlanner.Outcome outcome = operationPlanner.plan(request.intentId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status().name());
        if (outcome.operationId() != null) {
            body.put("operation_id", outcome.operationId().toString());
            body.put("resource_epoch", outcome.resourceEpoch());
        }
        if (outcome.rejectReason() != null) {
            body.put("reason", outcome.rejectReason());
        }
        return body;
    }
}
