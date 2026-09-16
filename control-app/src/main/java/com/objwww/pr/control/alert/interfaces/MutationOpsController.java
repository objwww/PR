package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.mutation.OperationLedgerStore;
import com.objwww.pr.control.alert.application.mutation.OperationPlanner;
import com.objwww.pr.control.alert.application.mutation.ResourceLockStore;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
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
    private final com.objwww.pr.control.alert.application.approval.ApprovalSuspensionService
            suspensions;
    private final com.objwww.pr.control.alert.application.approval.ApprovalShadowReader
            shadowReader;
    private final com.objwww.pr.control.alert.application.approval.GuardianAutoDecisionService
            guardianFlow;
    private final com.objwww.pr.control.alert.application.approval.ApprovalDecisionService
            decisionService;
    private final OperationLedgerStore operations;
    private final ResourceLockStore locks;
    private final RcaEventAppender events;
    private final com.objwww.pr.control.alert.application.approval.ApprovalStore approvals;

    public MutationOpsController(
            org.springframework.beans.factory.ObjectProvider<OperationPlanner> planner,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.approval.ApprovalSuspensionService>
                    suspensions,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.approval.ApprovalShadowReader>
                    shadowReader,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.approval.GuardianAutoDecisionService>
                    guardianFlow,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.approval.ApprovalDecisionService>
                    decisionService,
            org.springframework.beans.factory.ObjectProvider<OperationLedgerStore> operations,
            org.springframework.beans.factory.ObjectProvider<ResourceLockStore> locks,
            org.springframework.beans.factory.ObjectProvider<RcaEventAppender> events,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.approval.ApprovalStore> approvals) {
        this.planner = Objects.requireNonNull(planner);
        this.suspensions = suspensions == null ? null : suspensions.getIfAvailable();
        this.shadowReader = shadowReader == null ? null : shadowReader.getIfAvailable();
        this.guardianFlow = guardianFlow == null ? null : guardianFlow.getIfAvailable();
        this.decisionService = decisionService == null ? null : decisionService.getIfAvailable();
        this.operations = operations == null ? null : operations.getIfAvailable();
        this.locks = locks == null ? null : locks.getIfAvailable();
        this.events = events == null ? null : events.getIfAvailable();
        this.approvals = approvals == null ? null : approvals.getIfAvailable();
    }

    public record PlanRequest(UUID intentId) {
    }

    public record SuspendRequest(UUID runId, UUID requestId) {
    }

    public record ResumeRequest(UUID runId) {
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

    /** durable suspension（PC-C3 §2.10）：挂起等待审批（wall clock 不冻结，对账豁免） */
    @PostMapping("/suspend")
    public Map<String, Object> suspend(@RequestBody SuspendRequest request) {
        Objects.requireNonNull(request.runId(), "runId 必填");
        Objects.requireNonNull(request.requestId(), "requestId 必填");
        if (suspensions == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "SUSPENSION_FACE_NOT_ASSEMBLED");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("suspension_id", suspensions
                .suspend(request.runId(), request.requestId()).toString());
        body.put("status", "SUSPENDED");
        return body;
    }

    /** 恢复（落 human_wait 秒——双时钟的人等待单列） */
    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestBody ResumeRequest request) {
        Objects.requireNonNull(request.runId(), "runId 必填");
        if (suspensions == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "SUSPENSION_FACE_NOT_ASSEMBLED");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resumed", suspensions.resume(request.runId()));
        return body;
    }

    /** AM8 人工决策入口：PENDING 审批的人类裁决（approver 不得冒用 guardian 前缀） */
    public record DecideRequest(UUID requestId, String approverId, String approverRole,
            boolean approved) {
    }

    @PostMapping("/decide")
    public Map<String, Object> decide(@RequestBody DecideRequest request) {
        Objects.requireNonNull(request.requestId(), "requestId 必填");
        if (decisionService == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "APPROVAL_FACE_NOT_ASSEMBLED");
        }
        if (request.approverId() == null || request.approverId().startsWith("guardian:")) {
            return Map.of("status", "REJECTED", "reason", "INVALID_APPROVER");
        }
        try {
            String state = decisionService.decide(request.requestId(), request.approverId(),
                    request.approverRole(), request.approved());
            return Map.of("status", "OK", "approval_state", state);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 显式拒绝面（终态拒新决策/同人重复/审批不存在）——不走 /error 重派发
            // （异常穿透会被安全链 401 化，PA-BUG 登记见 PE-E2 台账）
            return Map.of("status", "REJECTED", "reason", e.getMessage());
        }
    }

    /** AM8 人工裁决：ESCALATED operation 的终裁（COMPLETED/FAILED_CONFIRMED），锁随裁决释放 */
    public record OperateRequest(UUID operationId, String ruling, String operatorId) {
    }

    @PostMapping("/operate")
    public Map<String, Object> operate(@RequestBody OperateRequest request) {
        Objects.requireNonNull(request.operationId(), "operationId 必填");
        Objects.requireNonNull(request.operatorId(), "operatorId 必填");
        var target = "COMPLETED".equals(request.ruling())
                ? com.objwww.pr.control.alert.domain.mutation.OperationStatus.COMPLETED
                : "FAILED_CONFIRMED".equals(request.ruling())
                ? com.objwww.pr.control.alert.domain.mutation.OperationStatus.FAILED_CONFIRMED
                : null;
        if (target == null) {
            return Map.of("status", "REJECTED", "reason", "INVALID_RULING");
        }
        if (operations == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "MUTATION_FACE_NOT_ASSEMBLED");
        }
        var operation = operations.findById(request.operationId()).orElse(null);
        if (operation == null || operation.status()
                != com.objwww.pr.control.alert.domain.mutation.OperationStatus.ESCALATED) {
            return Map.of("status", "REJECTED", "reason", "NOT_ESCALATED");
        }
        boolean advanced = operations.transition(request.operationId(),
                com.objwww.pr.control.alert.domain.mutation.OperationStatus.ESCALATED,
                target, java.time.Instant.now());
        if (!advanced) {
            return Map.of("status", "REJECTED", "reason", "CAS_LOST");
        }
        boolean lockReleased = operation.resourceUid() != null
                && locks != null
                && locks.releaseOnTerminalState(operation.resourceUid(),
                request.operationId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "OPERATION_RULING");
        payload.put("operation_id", request.operationId().toString());
        payload.put("ruling", request.ruling());
        payload.put("operator_id", request.operatorId());
        payload.put("lock_released", lockReleased);
        events.append(operation.runId(), new RcaEventAppender.EventDraft(UUID.randomUUID(),
                "OPERATION_RULING", com.objwww.pr.control.alert.application.mutation
                .OperationPlanner.CanonicalEventJson.canonicalize(payload)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("ruling", request.ruling());
        body.put("lock_released", lockReleased);
        return body;
    }

    /** shadow 观测面（§5 Phase C 观测清单计数投影 + human_wait 合计） */
    @org.springframework.web.bind.annotation.GetMapping("/shadow-summary")
    public Map<String, Object> shadowSummary() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (shadowReader != null) {
            body.putAll(shadowReader.summary());
        }
        if (suspensions != null) {
            var stats = suspensions.stats();
            body.put("suspensions_total", stats.total());
            body.put("suspensions_active", stats.active());
            body.put("human_wait_seconds_total", stats.humanWaitSeconds());
        }
        return body;
    }

    /** PE-E1：Guardian 自动决策（低危 R2 SAFE 自动批准 / UNSAFE 拒 / UNCERTAIN 转人工） */
    @PostMapping("/auto-decide")
    public Map<String, Object> autoDecide(@RequestBody PlanRequest request) {
        Objects.requireNonNull(request.intentId(), "intentId 必填");
        if (guardianFlow == null) {
            return Map.of("status", "UNAVAILABLE", "reason", "GUARDIAN_FACE_NOT_ASSEMBLED");
        }
        var outcome = guardianFlow.autoDecide(request.intentId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verdict", outcome.verdict());
        body.put("approval_state", outcome.approvalState());
        body.put("reason", outcome.reason());
        if (outcome.requestId() != null) {
            body.put("request_id", outcome.requestId().toString());
        }
        return body;
    }

    // ------------------------------------------------ 前端产品化波次1：审批处置页查询面

    /** 待审批清单（PENDING + 已投票计数；审批处置页主列表） */
    @org.springframework.web.bind.annotation.GetMapping("/pending-approvals")
    public Map<String, Object> pendingApprovals() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (approvals == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "APPROVAL_FACE_NOT_ASSEMBLED");
            return body;
        }
        java.time.Instant now = java.time.Instant.now();
        var rows = approvals.listPendingRequests(now);
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (var r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("request_id", r.requestId().toString());
            item.put("intent_id", r.intentId().toString());
            item.put("run_id", r.runId().toString());
            item.put("action_id", r.actionId());
            item.put("risk", r.risk());
            item.put("required_approvers", r.requiredApprovers());
            item.put("requested_at", r.requestedAt().toString());
            item.put("expires_at", r.expiresAt().toString());
            item.put("approved_count", r.approvedCount());
            item.put("denied_count", r.deniedCount());
            items.add(item);
        }
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    /** 升级人工清单（ESCALATED 操作 + 锁状态；审批处置页裁决列表） */
    @org.springframework.web.bind.annotation.GetMapping("/escalations")
    public Map<String, Object> escalations() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (operations == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "MUTATION_FACE_NOT_ASSEMBLED");
            return body;
        }
        var ids = operations.idsInStatus(
                com.objwww.pr.control.alert.domain.mutation.OperationStatus.ESCALATED);
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (UUID id : ids) {
            var op = operations.findById(id).orElse(null);
            if (op == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operation_id", op.operationId().toString());
            item.put("run_id", op.runId().toString());
            item.put("action_id", op.actionId());
            item.put("resource_uid", op.resourceUid());
            item.put("dry_run", op.dryRun());
            item.put("prepared_at", op.preparedAt() == null ? null : op.preparedAt().toString());
            item.put("params_json", op.paramsJson());
            if (locks != null && op.resourceUid() != null) {
                var lock = locks.find(op.resourceUid()).orElse(null);
                item.put("lock_state", lock == null ? null : lock.state());
            }
            items.add(item);
        }
        body.put("status", "OK");
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }
}
