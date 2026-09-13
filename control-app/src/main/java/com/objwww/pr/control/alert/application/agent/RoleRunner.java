package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 通用角色运行器契约（R7-X2，v2.1 §十一.1）：按 Profile 声明的 {@code runtime_kind}
 * 解析受审查运行器——"配置热更新"不意味着 Java 实现可热插拔；未部署的运行器类型
 * 在目录解析期显式拒绝（{@link RunnerDirectory}），不接受模型上传 Java 类或任意脚本。
 *
 * <p>运行器只做一步有界驱动，不直接改任务状态行（READY/LEASED/RUNNING/DONE/DEAD
 * 迁移归执行器/worker 面）；输入由持久绑定 + 冻结 Profile 装配，恢复面不猜角色。
 * 角色新增不产生第二条执行入口（§六）。
 */
public interface RoleRunner {

    /** 本运行器执行的 runtime_kind（与 RoleRuntimeKind 常量对齐） */
    String runtimeKind();

    /** 一步有界驱动（任务终态推进归执行器） */
    RoleDriveResult drive(RoleDriveRequest request);

    /** 驱动请求：持久绑定 + 冻结 Profile + 驱动上下文（执行器装配，禁 ThreadLocal） */
    record RoleDriveRequest(
            RcaTask task,
            TaskExecutionBinding binding,
            AgentProfile profile,
            SingleToolEvidenceAgent.CallContext callContext,
            String startEpoch,
            String endEpoch) {

        public RoleDriveRequest {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(callContext, "callContext");
        }
    }

    /** 驱动结局（单工具面四态 + 主 Runner 面三态共用一个封闭集） */
    enum RoleDriveOutcome {
        EVIDENCE_PRODUCED, NO_DATA, FAILED,
        /** 主 Runner：委派批获批，本轮子任务待收官（唤醒后再驱动） */
        WAITING_CHILDREN,
        /** 主 Runner：FINAL 提案已落检查点，待报告相位消费 */
        FINAL_READY,
        /** 主 Runner：委派批全拒（封闭原因码），主循环有界继续 */
        DELEGATE_REJECTED
    }

    /** 驱动结果（FAILED 时 reason = 模型可见原因码；evidenceIds 只含本步新证据）。
     * RV04：childResult = 结构化子任务结果（BA-142 回执生产面）——确定性角色如实
     * 声明能力边界；null = legacy 结果（回执面回退机械映射）。 */
    record RoleDriveResult(RoleDriveOutcome outcome, List<UUID> evidenceIds, String reason,
            ChildResult childResult) {

        /** RV04：结构化子任务结果（协议已有 findings/support_refs/counter_refs/
         * missing_information 四清单，不另造回执表） */
        public record ChildResult(List<String> findings, List<String> supportRefs,
                List<String> counterRefs, List<String> missingInformation) {

            public ChildResult {
                findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
                supportRefs = List.copyOf(Objects.requireNonNull(supportRefs,
                        "supportRefs"));
                counterRefs = List.copyOf(Objects.requireNonNull(counterRefs,
                        "counterRefs"));
                missingInformation = List.copyOf(Objects.requireNonNull(
                        missingInformation, "missingInformation"));
            }
        }

        public RoleDriveResult {
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            Objects.requireNonNull(outcome, "outcome");
        }

        /** 兼容构造（无结构化子结果） */
        public RoleDriveResult(RoleDriveOutcome outcome, List<UUID> evidenceIds,
                String reason) {
            this(outcome, evidenceIds, reason, null);
        }

        public static RoleDriveResult of(RoleDriveOutcome outcome) {
            return new RoleDriveResult(outcome, List.of(), null);
        }

        public static RoleDriveResult failed(String reason) {
            return new RoleDriveResult(RoleDriveOutcome.FAILED, List.of(), reason);
        }
    }
}
