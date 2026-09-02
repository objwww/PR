package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.sandbox.SandboxJob;
import com.objwww.pr.control.domain.sandbox.SandboxJobRepository;
import com.objwww.pr.control.domain.sandbox.ToolCall;
import com.objwww.pr.control.domain.sandbox.ToolCallRepository;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Broker 心跳与作业终态上报服务（M4 §4.2 生命周期，Broker → Control 回调）。
 *
 * <p>Broker 职责：
 * <ul>
 *   <li>定期心跳续租（防租约过期 → Recovery reaper 回收）</li>
 *   <li>作业完成/失败后上报终态（写 tool_call + sandbox_job，epoch fencing）</li>
 * </ul>
 */
@Service
@Profile("docker")
public class BrokerCallbackService {

    private final SandboxJobRepository sandboxJobRepository;
    private final ToolCallRepository toolCallRepository;

    public BrokerCallbackService(SandboxJobRepository sandboxJobRepository,
                                 ToolCallRepository toolCallRepository) {
        this.sandboxJobRepository = sandboxJobRepository;
        this.toolCallRepository = toolCallRepository;
    }

    /**
     * Broker 心跳续租（延长租约 + 更新心跳时间戳）。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch（CAS fencing）
     * @param leaseDurationSeconds 续租时长（秒）
     * @return true 续租成功，false epoch 不匹配（租约已失效）
     */
    public boolean renewLease(UUID jobId, long expectedEpoch, int leaseDurationSeconds) {
        return sandboxJobRepository.renewLease(jobId, expectedEpoch, leaseDurationSeconds);
    }

    /**
     * Broker 上报作业成功完成（ToolCall SUCCEEDED + SandboxJob SUCCEEDED，事务原子性）。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch（CAS fencing）
     * @param containerId 容器 ID
     * @param exitCode 退出码（0）
     * @param observationDigest 工具观测 digest（→artifact TOOL_OBSERVATION）
     * @param observationSummary 观测前 200 字简化
     * @param observationBytes 观测字节数
     * @param truncated 观测是否被截断
     * @param resultDigest 作业结果 digest（→artifact JOB_RESULT）
     * @param logDigest 容器日志 digest（→artifact JOB_LOG）
     * @return true 上报成功，false epoch 不匹配（租约已失效）
     */
    @Transactional
    public boolean reportSuccess(UUID jobId, long expectedEpoch, String containerId, int exitCode,
                                 Digest observationDigest, String observationSummary,
                                 long observationBytes, boolean truncated,
                                 Digest resultDigest, Digest logDigest) {
        Optional<SandboxJob> jobOpt = sandboxJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return false;
        }

        SandboxJob job = jobOpt.get();

        // 1. 更新 ToolCall → SUCCEEDED
        Optional<ToolCall> toolCallOpt = toolCallRepository.findById(job.toolCallId());
        if (toolCallOpt.isEmpty()) {
            throw new IllegalStateException("ToolCall not found for job " + jobId);
        }

        ToolCall toolCall = toolCallOpt.get();
        toolCall.complete(exitCode, observationDigest, observationSummary, observationBytes, truncated);
        boolean toolCallUpdated = toolCallRepository.update(toolCall, expectedEpoch);
        if (!toolCallUpdated) {
            return false; // epoch 不匹配
        }

        // 2. 更新 SandboxJob → SUCCEEDED
        job.complete(containerId, exitCode, resultDigest, logDigest);
        boolean jobUpdated = sandboxJobRepository.update(job, expectedEpoch);
        return jobUpdated;
    }

    /**
     * Broker 上报作业失败（ToolCall FAILED + SandboxJob FAILED，事务原子性）。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch（CAS fencing）
     * @param containerId 容器 ID（可能为 null，容器启动失败时）
     * @param exitCode 退出码（非 0）
     * @param observationDigest 工具观测 digest（错误输出）
     * @param observationSummary 观测前 200 字简化
     * @param observationBytes 观测字节数
     * @param truncated 观测是否被截断
     * @param logDigest 容器日志 digest
     * @param errorCode 结构化错误码（CONTAINER_START_FAILED/POLICY_REJECTION 等）
     * @param sanitizedMessage 脱敏后错误消息
     * @param failureClass 失败分类（决定是否可重试）
     * @return true 上报成功，false epoch 不匹配（租约已失效）
     */
    @Transactional
    public boolean reportFailure(UUID jobId, long expectedEpoch, String containerId, int exitCode,
                                 Digest observationDigest, String observationSummary,
                                 long observationBytes, boolean truncated, Digest logDigest,
                                 String errorCode, String sanitizedMessage, FailureClass failureClass) {
        Optional<SandboxJob> jobOpt = sandboxJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return false;
        }

        SandboxJob job = jobOpt.get();

        // 1. 更新 ToolCall → FAILED
        Optional<ToolCall> toolCallOpt = toolCallRepository.findById(job.toolCallId());
        if (toolCallOpt.isEmpty()) {
            throw new IllegalStateException("ToolCall not found for job " + jobId);
        }

        ToolCall toolCall = toolCallOpt.get();
        toolCall.fail(exitCode, observationDigest, observationSummary, observationBytes, truncated);
        boolean toolCallUpdated = toolCallRepository.update(toolCall, expectedEpoch);
        if (!toolCallUpdated) {
            return false; // epoch 不匹配
        }

        // 2. 更新 SandboxJob → FAILED
        job.fail(containerId, exitCode, logDigest, errorCode, sanitizedMessage, failureClass);
        boolean jobUpdated = sandboxJobRepository.update(job, expectedEpoch);
        return jobUpdated;
    }

    /**
     * Broker 上报作业超时（ToolCall FAILED + SandboxJob TIMED_OUT，事务原子性）。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch（CAS fencing）
     * @param containerId 容器 ID
     * @param logDigest 容器日志 digest
     * @return true 上报成功，false epoch 不匹配（租约已失效）
     */
    @Transactional
    public boolean reportTimeout(UUID jobId, long expectedEpoch, String containerId, Digest logDigest) {
        Optional<SandboxJob> jobOpt = sandboxJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return false;
        }

        SandboxJob job = jobOpt.get();

        // 1. 更新 ToolCall → FAILED（超时按失败处理，exit_code = -1）
        Optional<ToolCall> toolCallOpt = toolCallRepository.findById(job.toolCallId());
        if (toolCallOpt.isEmpty()) {
            throw new IllegalStateException("ToolCall not found for job " + jobId);
        }

        ToolCall toolCall = toolCallOpt.get();
        Digest emptyObservation = null; // 超时无观测输出
        toolCall.fail(-1, emptyObservation, "TIMEOUT", 0L, false);
        boolean toolCallUpdated = toolCallRepository.update(toolCall, expectedEpoch);
        if (!toolCallUpdated) {
            return false; // epoch 不匹配
        }

        // 2. 更新 SandboxJob → TIMED_OUT
        job.timeout(containerId, logDigest);
        boolean jobUpdated = sandboxJobRepository.update(job, expectedEpoch);
        return jobUpdated;
    }

    /**
     * Broker 上报策略拒绝（ToolCall REJECTED + SandboxJob FAILED，事务原子性）。
     *
     * <p>场景：输入物料安全检查失败、输出违规等。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch（CAS fencing）
     * @param reason 拒绝原因（明文，不含敏感信息）
     * @return true 上报成功，false epoch 不匹配（租约已失效）
     */
    @Transactional
    public boolean reportRejection(UUID jobId, long expectedEpoch, String reason) {
        Optional<SandboxJob> jobOpt = sandboxJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return false;
        }

        SandboxJob job = jobOpt.get();

        // 1. 更新 ToolCall → REJECTED
        Optional<ToolCall> toolCallOpt = toolCallRepository.findById(job.toolCallId());
        if (toolCallOpt.isEmpty()) {
            throw new IllegalStateException("ToolCall not found for job " + jobId);
        }

        ToolCall toolCall = toolCallOpt.get();
        toolCall.reject(reason);
        boolean toolCallUpdated = toolCallRepository.update(toolCall, expectedEpoch);
        if (!toolCallUpdated) {
            return false; // epoch 不匹配
        }

        // 2. 更新 SandboxJob → FAILED（POLICY_REJECTION）
        job.fail(null, null, null, "POLICY_REJECTION", reason, FailureClass.POLICY_REJECTION);
        boolean jobUpdated = sandboxJobRepository.update(job, expectedEpoch);
        return jobUpdated;
    }
}
