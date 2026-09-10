package com.objwww.pr.control.alert.application.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return switch (result.outcome()) {
            case EVIDENCE_PRODUCED -> new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED,
                    result.evidenceIds(), null);
            case NO_DATA -> new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.NO_DATA, List.of(), null);
            case FAILED -> RoleRunner.RoleDriveResult.failed(result.errorClass());
        };
    }
}
