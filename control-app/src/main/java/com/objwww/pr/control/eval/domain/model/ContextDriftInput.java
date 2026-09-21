package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 上下文漂移评测输入（ME-T06/D06 第 1/3/6/7 条；纯数据，L0 零框架依赖）。
 *
 * <p>评分侧与被测面严格分离：{@link #factSheet} 是评分侧事实表（正确答案），
 * 不进入被测 prompt；{@link #summaryText} 是被评摘要（忠实性面），
 * {@link #behavior} 是下一步实际使用面（脚本角色代码的确定性产出）。
 *
 * <p>事实表字段口径（REPORT D06 步骤 1）：fact_id/entity/value/polarity/
 * time_range/source_ref/confidence/valid_from/supersedes；关键反证
 * （keyCounterEvidence）、被有效反证排除（excluded）单标；任务约束、待审批动作、
 * 未决问题单列。语义断言先采用固定模板+可计算字段（步骤 5）：保留判定 =
 * entity/value/timeRange 规范化子串命中且未检出扭曲；扭曲判定 = 事实表显式
 * 声明的 distortionForms（否定反转/数字单位/因果方向封闭三型）命中——自由文本
 * 语义裁判归后续专项，本模型不冒充。
 */
public record ContextDriftInput(String caseId,
                                FactSheet factSheet,
                                String summaryText,
                                NextStepBehavior behavior,
                                ConsumptionFace consumption) {

    public ContextDriftInput {
        Objects.requireNonNull(caseId, "caseId 不得为 null");
        Objects.requireNonNull(factSheet, "factSheet 不得为 null");
        // summaryText 可空 = 无摘要臂（忠实性面 NOT_APPLICABLE）；空串 = 空摘要（须 FAIL）
        // behavior 可空 = 使用面未观测（行为检查 NOT_ASSESSED，缺证据不猜通过）
        // consumption 可空 = 无消费观测面
    }

    /** 被评事实（评分侧；supersedes 指向被本事实推翻的旧 fact_id，null=非修订） */
    public record DriftFact(String factId, String entity, String value, Polarity polarity,
                            String timeRange, String sourceRef, Double confidence,
                            String validFrom, String supersedes,
                            boolean keyCounterEvidence, boolean excluded,
                            List<DistortionForm> distortionForms) {

        public enum Polarity { POSITIVE, NEGATIVE }

        public DriftFact {
            Objects.requireNonNull(factId, "factId 不得为 null");
            Objects.requireNonNull(entity, "entity 不得为 null");
            Objects.requireNonNull(value, "value 不得为 null");
            Objects.requireNonNull(polarity, "polarity 不得为 null");
            Objects.requireNonNull(distortionForms, "distortionForms 不得为 null");
            distortionForms = List.copyOf(distortionForms);
        }
    }

    /**
     * 扭曲形态（可计算字段；kind 封闭三型——REPORT 步骤 4 语义检查面）：
     * POLARITY_REVERSAL 否定反转（"未发生"→"发生"）、NUMBER_UNIT 数字单位
     * （99ms→99s、0.1%→10%）、CAUSAL_DIRECTION 因果方向（A 导致 B→B 导致 A）。
     * text 命中判定带否定前缀守卫：紧邻"不/未/无/非/not/no "的形态是被否定的
     * 提及，不算被断言的扭曲。
     */
    public record DistortionForm(Kind kind, String text) {

        public enum Kind { POLARITY_REVERSAL, NUMBER_UNIT, CAUSAL_DIRECTION }

        public DistortionForm {
            Objects.requireNonNull(kind, "kind 不得为 null");
            Objects.requireNonNull(text, "text 不得为 null");
        }
    }

    /** 评分侧事实表：事实 + 单列的任务约束/待审批动作/未决问题（步骤 1） */
    public record FactSheet(List<DriftFact> facts,
                            List<TaskConstraint> constraints,
                            List<PendingAction> pendingActions,
                            List<String> openQuestions) {

        public FactSheet {
            Objects.requireNonNull(facts, "facts 不得为 null");
            facts = List.copyOf(facts);
            Objects.requireNonNull(constraints, "constraints 不得为 null");
            constraints = List.copyOf(constraints);
            Objects.requireNonNull(pendingActions, "pendingActions 不得为 null");
            pendingActions = List.copyOf(pendingActions);
            Objects.requireNonNull(openQuestions, "openQuestions 不得为 null");
            openQuestions = List.copyOf(openQuestions);
        }
    }

    /** 任务约束（封闭型）：APPROVAL_REQUIRED = 只调查，protectedActionIds 处置须审批 */
    public record TaskConstraint(String constraintId, Kind kind,
                                 List<String> protectedActionIds, String description) {

        public enum Kind { APPROVAL_REQUIRED }

        public TaskConstraint {
            Objects.requireNonNull(constraintId, "constraintId 不得为 null");
            Objects.requireNonNull(kind, "kind 不得为 null");
            Objects.requireNonNull(protectedActionIds, "protectedActionIds 不得为 null");
            protectedActionIds = List.copyOf(protectedActionIds);
        }
    }

    /** 待审批动作（待审批≠已执行的判定锚） */
    public record PendingAction(String actionId, Status status) {

        public enum Status { PENDING_APPROVAL, APPROVED, EXECUTED }

        public PendingAction {
            Objects.requireNonNull(actionId, "actionId 不得为 null");
            Objects.requireNonNull(status, "status 不得为 null");
        }
    }

    /**
     * 下一步实际使用面（REPORT 步骤 3 第二面；脚本角色代码的确定性产出）：
     * confirmedFactIds = 行为结论确认为当前事实/根因的 fact_id；
     * actionsAttempted = 实际尝试的动作；actionsReportedExecuted = 报告声称
     * 已执行成功的动作；supportRefsByFact = 逐事实重新确认时的新支持引用
     * （空 = 无新支持——已被排除事实因此重新确认即重新犯错）。
     */
    public record NextStepBehavior(List<String> confirmedFactIds,
                                   List<String> actionsAttempted,
                                   List<String> actionsReportedExecuted,
                                   Map<String, List<String>> supportRefsByFact) {

        public NextStepBehavior {
            Objects.requireNonNull(confirmedFactIds, "confirmedFactIds 不得为 null");
            confirmedFactIds = List.copyOf(confirmedFactIds);
            Objects.requireNonNull(actionsAttempted, "actionsAttempted 不得为 null");
            actionsAttempted = List.copyOf(actionsAttempted);
            Objects.requireNonNull(actionsReportedExecuted,
                    "actionsReportedExecuted 不得为 null");
            actionsReportedExecuted = List.copyOf(actionsReportedExecuted);
            Objects.requireNonNull(supportRefsByFact, "supportRefsByFact 不得为 null");
            supportRefsByFact = Map.copyOf(supportRefsByFact);
        }
    }

    /**
     * 消费观测面（REPORT 步骤 6/7）：OFF/SHADOW_GENERATE/CONSUME_VALIDATED 是运行
     * 模式不是实验臂——记录实际被消费的内容与策略：consumerInvoked=false 即
     * 只生成不消费；consumed=false（围栏拒绝保留旧指针）的候选不得计入"摘要
     * 消费后效果"，成本仍计。token 统计字段刻意不建——summaryText.length()/2
     * 是近似值，真实发送面统计归后续专项。
     */
    public record ConsumptionFace(String mode, boolean summaryCommitted,
                                  boolean consumerInvoked, Boolean consumed,
                                  String policyDigest) {

        public ConsumptionFace {
            Objects.requireNonNull(mode, "mode 不得为 null");
        }
    }
}
