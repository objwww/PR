package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;

import java.util.List;
import java.util.Optional;

/**
 * RCA task 物理执行抽象（T06 worker 的执行插槽；T07 由 HolmesInvestigationExecutor 实现）。
 *
 * <p>实现约束（§4.1 时序 / AFT-30 纪律）：
 * <ul>
 *   <li>HTTP 调用发生在任何 DB 事务之外；外部调用账本 insertStarted 在触网前独立短事务
 *       （写失败 = 零触网）——这两条由实现方（HolmesInvestigationExecutor）保证；</li>
 *   <li>实现不得写 rca_task/rca_run/incident——收尾统一走
 *       {@link RcaRunOrchestrator#finishTask}（epoch 栅栏 + §6.7 单事务算法）；</li>
 *   <li>告警 labels/annotations 属不可信内容，透传给外部前按 §6.6-5 处理。</li>
 * </ul>
 */
public interface RcaTaskExecutor {

    /**
     * 执行一轮调查。
     *
     * @param attempt    本次物理尝试（STARTED 已落库；id 作为报告归属）
     * @param heartbeat 长调查期间的心跳续租回调（实现方在等待外部响应的循环里周期调用）
     */
    ExecutionResult execute(RcaTask task, RcaRun run, Incident incident, RcaAttempt attempt,
                            Runnable heartbeat);

    /**
     * 报告内容子集（无 DB 归属——runId/attemptId 由 finishTask 收尾时以真实值铸造）。
     */
    record ReportContent(int schemaVersion, ValidationStatus validationStatus,
                         List<String> validationErrors, String packageJson, String rawText,
                         String model, Integer promptTokens, Integer completionTokens,
                         Integer totalTokens, boolean usageMissing) {
        public ReportContent {
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }
    }

    /** 执行结果：report = 结构验证通过的报告内容（失败/重试时 empty） */
    record ExecutionResult(Outcome outcome, String errorClass, String errorCode,
                           String errorDetail, Optional<ReportContent> report) {

        public enum Outcome {SUCCEEDED, FAILED_RETRYABLE, FAILED_TERMINAL}

        public static ExecutionResult success(ReportContent report) {
            return new ExecutionResult(Outcome.SUCCEEDED, null, null, null, Optional.of(report));
        }

        public static ExecutionResult retryable(String errorClass, String detail) {
            return new ExecutionResult(Outcome.FAILED_RETRYABLE, errorClass, null, detail, Optional.empty());
        }

        public static ExecutionResult terminal(String errorClass, String detail) {
            return new ExecutionResult(Outcome.FAILED_TERMINAL, errorClass, null, detail, Optional.empty());
        }
    }
}
