package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.sandbox.SandboxJob;
import com.objwww.pr.control.domain.sandbox.SandboxJobRepository;
import com.objwww.pr.control.domain.sandbox.ToolCall;
import com.objwww.pr.control.domain.sandbox.ToolCallRepository;
import com.objwww.pr.shared.sandbox.JobSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 沙箱作业生命周期服务（M4 §4.2，应用层编排）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>submit：提交新作业（ToolCall + SandboxJob 双入表，PENDING 状态）</li>
 *   <li>getJobStatus：查询作业状态（供 Agent loop 轮询结果）</li>
 *   <li>getToolCallResult：查询工具调用结果（观测 digest + 终态）</li>
 * </ul>
 */
@Service
@Profile("docker")
public class SandboxJobService {

    private final SandboxJobRepository sandboxJobRepository;
    private final ToolCallRepository toolCallRepository;
    private final ObjectMapper objectMapper;

    public SandboxJobService(SandboxJobRepository sandboxJobRepository,
                             ToolCallRepository toolCallRepository,
                             ObjectMapper objectMapper) {
        this.sandboxJobRepository = sandboxJobRepository;
        this.toolCallRepository = toolCallRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交新沙箱作业（ToolCall + SandboxJob 双入表，事务原子性）。
     *
     * <p>调用方（Agent loop）需先通过 {@code SandboxJobSpecFactory} 铸造 JobSpec。
     *
     * @param jobSpec 作业规格（已通过 PolicyEngine 审核）
     * @param reviewRunId 所属 review run ID
     * @param runStepId 所属 run step ID
     * @param attemptId 所属 attempt ID
     * @param callSeq attempt 内工具调用序号
     * @param toolName 工具名称
     * @param toolArgsJson 工具参数 JSON
     * @return 已创建的作业 ID
     */
    @Transactional
    public UUID submit(JobSpec jobSpec, UUID reviewRunId, UUID runStepId, UUID attemptId,
                       int callSeq, String toolName, String toolArgsJson) {
        // 1. 创建 ToolCall 记录（RUNNING 状态）
        UUID toolCallId = UUID.randomUUID();
        ToolCall toolCall = ToolCall.createRunning(
            toolCallId, reviewRunId, runStepId, attemptId, callSeq,
            toolName, toolArgsJson, jobSpec.leaseEpoch()
        );
        toolCallRepository.save(toolCall);

        // 2. 创建 SandboxJob 记录（PENDING 状态，单向 FK → tool_call）
        UUID jobId = jobSpec.jobId();
        String jobSpecJson = serializeJobSpec(jobSpec);
        SandboxJob job = SandboxJob.createPending(
            jobId, toolCallId, reviewRunId, runStepId, attemptId, jobSpecJson
        );
        sandboxJobRepository.save(job);

        return jobId;
    }

    /**
     * 查询作业状态。
     *
     * @param jobId 作业 ID
     * @return 作业实体，不存在返回 empty
     */
    public Optional<SandboxJob> getJobStatus(UUID jobId) {
        return sandboxJobRepository.findById(jobId);
    }

    /**
     * 查询工具调用结果（观测 digest + 终态）。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具调用实体，不存在返回 empty
     */
    public Optional<ToolCall> getToolCallResult(UUID toolCallId) {
        return toolCallRepository.findById(toolCallId);
    }

    /**
     * 按 tool_call_id 查询作业（单向 FK，一对一）。
     *
     * @param toolCallId 工具调用 ID
     * @return 作业实体，不存在返回 empty
     */
    public Optional<SandboxJob> getJobByToolCallId(UUID toolCallId) {
        return sandboxJobRepository.findByToolCallId(toolCallId);
    }

    private String serializeJobSpec(JobSpec jobSpec) {
        try {
            return objectMapper.writeValueAsString(jobSpec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize JobSpec", e);
        }
    }
}
