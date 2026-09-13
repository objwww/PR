package com.objwww.pr.control.alert.application.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 旧确定性三角色的兼容适配运行器（R7-X2，v2.1 §十一.1 "旧确定性执行器通过兼容
 * 适配接入"）：role_id → 单工具只读 Agent 的装配期映射，替代
 * NativeInvestigationExecutor.drive 里按业务 taskKey 的固定 switch——执行器不再
 * 认识角色名，分派面 = 持久绑定.roleId + Profile.runtime_kind。
 *
 * <p>行为零改动硬约束：Agent 本体（SingleToolEvidenceAgent 固定顺序入口）、查询
 * 参数构造与状态迁移面全部原样，只换分派键（taskKey → 绑定 roleId）。绑定角色
 * 无对应适配 = CAPABILITY_UNAVAILABLE 显式拒绝（不猜最接近的 Agent 顶替）。
 */
public final class SingleToolRoleRunner implements RoleRunner {

    /** 角色查询处理器（装配期闭包：Agent + 各角色查询参数构造） */
    @FunctionalInterface
    public interface RoleQueryHandler {

        SingleToolEvidenceAgent.AgentResult investigate(
                SingleToolEvidenceAgent.CallContext ctx, String startEpoch, String endEpoch);
    }

    private final Map<String, RoleQueryHandler> handlersByRole = new LinkedHashMap<>();

    public SingleToolRoleRunner(Map<String, RoleQueryHandler> handlersByRole) {
        Objects.requireNonNull(handlersByRole, "handlersByRole");
        if (handlersByRole.isEmpty()) {
            throw new IllegalArgumentException("兼容适配至少一个角色处理器");
        }
        this.handlersByRole.putAll(handlersByRole);
    }

    @Override
    public String runtimeKind() {
        return com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind
                .DETERMINISTIC_SINGLE_TOOL;
    }

    @Override
    public RoleRunner.RoleDriveResult drive(RoleRunner.RoleDriveRequest request) {
        RoleQueryHandler handler = handlersByRole.get(request.binding().roleId());
        if (handler == null) {
            throw new RunnerDirectory.CapabilityUnavailableException(
                    "CAPABILITY_UNAVAILABLE: 绑定角色 " + request.binding().roleId()
                            + "@" + request.binding().roleVersion()
                            + " 无兼容单工具适配（runtime_kind="
                            + runtimeKind() + "）");
        }
        SingleToolEvidenceAgent.AgentResult result = handler.investigate(
                request.callContext(), request.startEpoch(), request.endEpoch());
        // RV04/BA-142/T17/T18：确定性单工具角色的诚实结构化回执—— findings 只描述
        // "查了什么"，supportRefs=真实证据行；无业务结论能力如实写缺口，不制造假
        // 反证凑 witness（反证空=无反证，与"失败缺口"共同满足结构契约）
        return switch (result.outcome()) {
            case EVIDENCE_PRODUCED -> {
                List<String> refs = result.evidenceIds().stream()
                        .map(UUID::toString).toList();
                yield new RoleRunner.RoleDriveResult(
                        RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED,
                        result.evidenceIds(), null,
                        new RoleRunner.RoleDriveResult.ChildResult(
                                List.of("确定性单工具角色完成只读查询（无业务结论能力）"),
                                refs, List.of(),
                                List.of("确定性单工具角色无法回答委派 question 的业务"
                                        + "推理；仅提供原始证据行，结论由主任务消费面"
                                        + "产出")));
            }
            case NO_DATA -> new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.NO_DATA, List.of(), null,
                    new RoleRunner.RoleDriveResult.ChildResult(
                            List.of("查询成功零数据（诚实呈现，不伪造统计）"),
                            List.of(), List.of(),
                            List.of("窗口内无数据，未产出证据行")));
            case FAILED -> RoleRunner.RoleDriveResult.failed(result.errorClass());
        };
    }
}
