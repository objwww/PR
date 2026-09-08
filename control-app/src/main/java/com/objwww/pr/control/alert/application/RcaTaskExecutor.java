package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.shared.Digest;

import java.util.List;
import java.util.Optional;

/**
 * RCA task 物理执行抽象（T06 worker 的执行插槽；T07 首由 HolmesInvestigationExecutor 实现，M6-07 后由 NativeInvestigationExecutor 承载）。
 *
 * <p>实现约束（§4.1 时序 / AFT-30 纪律）：
 * <ul>
 *   <li>HTTP 调用发生在任何 DB 事务之外；外部调用账本 insertStarted 在触网前独立短事务
 *       （写失败 = 零触网）——这两条由实现方保证（首实现 HolmesInvestigationExecutor 已随 M6-07 退场删除）；</li>
 *   <li>实现不得写 rca_task/rca_run/incident——收尾统一走
 *       {@link RcaRunOrchestrator#finishTask}（epoch 栅栏 + §6.7 单事务算法）；</li>
 *   <li>告警 labels/annotations 属不可信内容，透传给外部前按 §6.6-5 处理。</li>
 * </ul>
 */
public interface RcaTaskExecutor {

    /**
     * 执行一轮调查。
     *
     * @param attempt    本次物理尝试（STARTED 已落库；id 同时是 investigation_result 主键，M3-04）
     * @param heartbeat 长调查期间的心跳续租回调（实现方在等待外部响应的循环里周期调用）
     */
    ExecutionResult execute(RcaTask task, RcaRun run, Incident incident, RcaAttempt attempt,
                            Runnable heartbeat);

    /**
     * 一次 attempt 的落档载体（M3-08；成功与 REJECTED_* 同权返回——验证失败也有完整落档，
     * INV-AM3-7）。rawText 为脱敏后原文；toolCalls 已按 attempt/run/generation 铸成域对象
     * （investigationResultId = attempt.id）。
     *
     * <p>samplingFingerprint（M5-04/V23）：采样指纹的 jsonb Map 形态（null = 无 LLM
     * 响应现场，触网前失败），随收尾事务落 rca_attempt 行。
     */
    record AttemptArtifact(int schemaVersion, ValidationStatus validationStatus,
                           List<String> validationErrors, String packageJson, String rawText,
                           EvidencePackageV2 typedPackage, List<RcaToolCall> toolCalls,
                           Digest rawDigest, Digest payloadDigest, String model,
                           Integer promptTokens, Integer completionTokens,
                           Integer totalTokens, boolean usageMissing,
                           java.util.Map<String, Object> samplingFingerprint) {
        public AttemptArtifact {
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** 执行结果：artifact = 响应可落档时的完整载体（传输失败/触网前失败为 empty） */
    record ExecutionResult(Outcome outcome, String errorClass, String errorCode,
                           String errorDetail, Optional<AttemptArtifact> artifact) {

        public enum Outcome {SUCCEEDED, FAILED_RETRYABLE, FAILED_TERMINAL}

        public static ExecutionResult success(AttemptArtifact artifact) {
            return new ExecutionResult(Outcome.SUCCEEDED, null, null, null, Optional.of(artifact));
        }

        public static ExecutionResult retryable(String errorClass, String detail) {
            return new ExecutionResult(Outcome.FAILED_RETRYABLE, errorClass, null, detail, Optional.empty());
        }

        public static ExecutionResult terminal(String errorClass, String detail) {
            return new ExecutionResult(Outcome.FAILED_TERMINAL, errorClass, null, detail, Optional.empty());
        }

        /** 结构/解析失败但有完整响应现场（REJECTED_* 落档路径，INV-AM3-7） */
        public static ExecutionResult terminalWithArtifact(String errorClass, String detail,
                                                           AttemptArtifact artifact) {
            return new ExecutionResult(Outcome.FAILED_TERMINAL, errorClass, null, detail,
                    Optional.of(artifact));
        }
    }
}
