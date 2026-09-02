package com.objwww.pr.broker.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.async.ResultCallback;
import com.objwww.pr.broker.client.ControlApiClient;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;
import com.objwww.pr.shared.sandbox.JobSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Job Executor 服务（M4 §4.4 Docker 容器执行）。
 *
 * <p>核心流程：
 * <ul>
 *   <li>1. 物料提取：从 Artifact 存储拉取 workspace tar.gz（SafeTarExtractor 安全解包）</li>
 *   <li>2. 容器启动：按 JobSpec 配置启动容器（安全剖面 + 资源限额 + 网络隔离）</li>
 *   <li>3. 执行监控：awaitCompletion 等待容器完成，定期心跳续租</li>
 *   <li>4. 结果回传：提取 /out 目录，上传到 Artifact 存储，上报终态到 Control</li>
 * </ul>
 */
@Service
public class JobExecutorService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutorService.class);

    private final DockerClient dockerClient;
    private final ControlApiClient controlApiClient;
    private final HeartbeatService heartbeatService;
    private final WorkspaceManager workspaceManager;
    private final SecurityProfileMapper securityProfileMapper;
    private final ResultExtractor resultExtractor;

    public JobExecutorService(DockerClient dockerClient,
                              ControlApiClient controlApiClient,
                              HeartbeatService heartbeatService,
                              WorkspaceManager workspaceManager,
                              SecurityProfileMapper securityProfileMapper,
                              ResultExtractor resultExtractor) {
        this.dockerClient = dockerClient;
        this.controlApiClient = controlApiClient;
        this.heartbeatService = heartbeatService;
        this.workspaceManager = workspaceManager;
        this.securityProfileMapper = securityProfileMapper;
        this.resultExtractor = resultExtractor;
    }

    /**
     * 执行作业（完整流程：物料提取 → 容器启动 → 等待完成 → 结果回传 → 上报终态）。
     *
     * @param claimedJob 已领取的作业
     */
    public void execute(ControlApiClient.ClaimedJob claimedJob) {
        String jobId = claimedJob.jobId;
        long leaseEpoch = claimedJob.leaseEpoch;
        JobSpec jobSpec = claimedJob.jobSpec;

        log.info("Executing job: jobId={}, imageRef={}", jobId, jobSpec.imageRef().toReference());

        String containerId = null;
        Path workspaceDir = null;
        try {
            // 1. 物料提取
            workspaceDir = workspaceManager.extractWorkspace(
                jobSpec.sourceSnapshotDigest(),
                jobSpec.inputDigests()
            );
            log.info("Workspace extracted: jobId={}, path={}", jobId, workspaceDir);

            // 2. 容器启动（安全剖面全字段）
            containerId = startContainer(jobSpec, workspaceDir);
            log.info("Container started: jobId={}, containerId={}", jobId, containerId);

            // 3. 启动心跳续租（后台线程）
            heartbeatService.startHeartbeat(jobId, leaseEpoch);

            // 4. 等待容器完成
            boolean completed = awaitCompletion(containerId, jobSpec.timeoutSeconds());

            // 5. 停止心跳
            heartbeatService.stopHeartbeat(jobId);

            if (!completed) {
                // 超时：kill 容器 + 收集日志 + 上报 TIMED_OUT
                dockerClient.killContainerCmd(containerId).exec();
                Digest logDigest = resultExtractor.extractLogs(containerId);
                controlApiClient.reportTimeout(jobId, leaseEpoch, containerId, logDigest);
                log.warn("Job timed out: jobId={}, containerId={}", jobId, containerId);
                return;
            }

            // 6. 检查退出码
            int exitCode = inspectExitCode(containerId);
            log.info("Container exited: jobId={}, containerId={}, exitCode={}", jobId, containerId, exitCode);

            // 7. 提取结果和日志
            Digest logDigest = resultExtractor.extractLogs(containerId);
            Digest observationDigest = resultExtractor.extractObservation(containerId);

            if (exitCode == 0) {
                // 成功：提取结果 + 上报 SUCCEEDED
                Digest resultDigest = resultExtractor.extractResult(containerId);
                controlApiClient.reportSuccess(
                    jobId, leaseEpoch, containerId, exitCode,
                    observationDigest, "Success", 0L, false,
                    resultDigest, logDigest
                );
                log.info("Job succeeded: jobId={}", jobId);
            } else {
                // 失败：上报 FAILED
                controlApiClient.reportFailure(
                    jobId, leaseEpoch, containerId, exitCode,
                    observationDigest, "Exit code: " + exitCode, 0L, false,
                    logDigest, "NON_ZERO_EXIT", "Container exited with code " + exitCode,
                    FailureClass.USER_CODE
                );
                log.warn("Job failed: jobId={}, exitCode={}", jobId, exitCode);
            }

        } catch (Exception e) {
            log.error("Job execution error: jobId=" + jobId, e);
            // 基础设施故障：上报 FAILED (INFRASTRUCTURE)
            try {
                Digest logDigest = containerId != null ? resultExtractor.extractLogs(containerId) : null;
                controlApiClient.reportFailure(
                    jobId, leaseEpoch, containerId, -1,
                    null, "Infrastructure error: " + e.getMessage(), 0L, false,
                    logDigest, "INFRASTRUCTURE_ERROR", sanitizeMessage(e.getMessage()),
                    FailureClass.INFRASTRUCTURE
                );
            } catch (Exception reportError) {
                log.error("Failed to report infrastructure error", reportError);
            }
        } finally {
            // 清理容器和工作区
            if (containerId != null) {
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    log.info("Container removed: containerId={}", containerId);
                } catch (Exception e) {
                    log.warn("Failed to remove container: containerId=" + containerId, e);
                }
            }
            if (workspaceDir != null) {
                workspaceManager.cleanupWorkspace(workspaceDir);
            }
        }
    }

    private String startContainer(JobSpec jobSpec, Path workspaceDir) {
        // 使用 SecurityProfileMapper 构建完整安全剖面（§4.5 全字段）
        HostConfig hostConfig = securityProfileMapper.buildHostConfig(jobSpec);

        // 添加 workspace 挂载（只读）
        Bind workspaceBind = new Bind(
            workspaceDir.toString(),
            new Volume("/workspace"),
            AccessMode.ro  // 只读
        );
        hostConfig.withBinds(workspaceBind);

        CreateContainerResponse container = dockerClient.createContainerCmd(jobSpec.imageRef().toReference())
            .withHostConfig(hostConfig)
            .withCmd(jobSpec.command())
            .withWorkingDir("/workspace")
            .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    private boolean awaitCompletion(String containerId, int timeoutSeconds) {
        try {
            ResultCallback.Adapter<WaitResponse> callback = new ResultCallback.Adapter<>();
            dockerClient.waitContainerCmd(containerId).exec(callback);
            return callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int inspectExitCode(String containerId) {
        var response = dockerClient.inspectContainerCmd(containerId).exec();
        var state = response.getState();
        return state.getExitCodeLong() != null ? state.getExitCodeLong().intValue() : -1;
    }

    private String sanitizeMessage(String message) {
        // 脱敏处理：移除路径等敏感信息（简化版）
        if (message == null) {
            return "";
        }
        // 截断到 200 字符
        String sanitized = message.length() > 200 ? message.substring(0, 200) : message;
        // TODO: 更复杂的脱敏逻辑（移除文件路径、IP 地址等）
        return sanitized;
    }
}
