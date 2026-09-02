package com.objwww.pr.broker.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Container;
import com.objwww.pr.broker.client.ControlApiClient;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;
import com.objwww.pr.shared.sandbox.JobSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UT-79~85: JobExecutorService 单元测试（容器生命周期管理）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>UT-79: 容器启动流程（物料提取 → 容器启动 → 心跳启动）</li>
 *   <li>UT-80: 容器正常完成（exitCode=0 → SUCCEEDED）</li>
 *   <li>UT-81: 容器失败退出（exitCode!=0 → FAILED/USER_CODE）</li>
 *   <li>UT-82: 容器超时（timeout → kill → TIMED_OUT）</li>
 *   <li>UT-83: 基础设施故障（异常 → FAILED/INFRASTRUCTURE）</li>
 *   <li>UT-84: 心跳生命周期（启动 → 续约 → 停止）</li>
 *   <li>UT-85: 资源清理（容器删除 + 工作区清理）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobExecutorServiceTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ControlApiClient controlApiClient;

    @Mock
    private HeartbeatService heartbeatService;

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SecurityProfileMapper securityProfileMapper;

    @Mock
    private ResultExtractor resultExtractor;

    @Mock
    private CreateContainerCmd createContainerCmd;

    @Mock
    private StartContainerCmd startContainerCmd;

    @Mock
    private WaitContainerCmd waitContainerCmd;

    @Mock
    private InspectContainerCmd inspectContainerCmd;

    @Mock
    private KillContainerCmd killContainerCmd;

    @Mock
    private RemoveContainerCmd removeContainerCmd;

    @Mock
    private CreateContainerResponse createContainerResponse;

    private JobExecutorService service;

    @BeforeEach
    void setUp() {
        service = new JobExecutorService(
            dockerClient,
            controlApiClient,
            heartbeatService,
            workspaceManager,
            securityProfileMapper,
            resultExtractor
        );
    }

    /**
     * UT-79: 容器启动流程验证（物料提取 → 容器创建 → 心跳启动）
     */
    @Test
    void execute_normalFlow_startsContainerAndHeartbeat() throws Exception {
        // TODO: 实现容器启动流程测试
        // 1. Mock 物料提取成功
        // 2. Mock 容器创建/启动成功
        // 3. 验证 heartbeatService.startHeartbeat() 被调用
    }

    /**
     * UT-80: 容器正常完成（exitCode=0）上报 SUCCEEDED
     */
    @Test
    void execute_containerExitsZero_reportsSuccess() throws Exception {
        // TODO: 实现正常完成测试
        // 1. Mock 容器 exitCode=0
        // 2. Mock 结果提取成功
        // 3. 验证 controlApiClient.reportSuccess() 被调用
        // 4. 验证参数：resultDigest, logDigest
    }

    /**
     * UT-81: 容器失败退出（exitCode!=0）上报 FAILED/USER_CODE
     */
    @Test
    void execute_containerExitsNonZero_reportsFailure() throws Exception {
        // TODO: 实现失败退出测试
        // 1. Mock 容器 exitCode=1
        // 2. 验证 controlApiClient.reportFailure() 被调用
        // 3. 验证 failureClass=USER_CODE
    }

    /**
     * UT-82: 容器超时，kill 容器并上报 TIMED_OUT
     */
    @Test
    void execute_containerTimeout_killsAndReportsTimeout() throws Exception {
        // TODO: 实现超时测试
        // 1. Mock awaitCompletion() 返回 false（超时）
        // 2. 验证 dockerClient.killContainerCmd() 被调用
        // 3. 验证 controlApiClient.reportTimeout() 被调用
    }

    /**
     * UT-83: 基础设施故障（物料提取失败）上报 FAILED/INFRASTRUCTURE
     */
    @Test
    void execute_workspaceExtractionFails_reportsInfrastructureError() throws Exception {
        // TODO: 实现基础设施故障测试
        // 1. Mock workspaceManager.extractWorkspace() 抛异常
        // 2. 验证 controlApiClient.reportFailure() 被调用
        // 3. 验证 failureClass=INFRASTRUCTURE
    }

    /**
     * UT-84: 心跳生命周期（启动 → 容器完成 → 停止）
     */
    @Test
    void execute_heartbeatLifecycle_startsAndStops() throws Exception {
        // TODO: 实现心跳生命周期测试
        // 1. 验证 heartbeatService.startHeartbeat() 在容器启动后调用
        // 2. 验证 heartbeatService.stopHeartbeat() 在容器完成后调用
        // 3. 验证即使异常也会停止心跳
    }

    /**
     * UT-85: 资源清理（容器删除 + 工作区清理）
     */
    @Test
    void execute_cleanup_removesContainerAndWorkspace() throws Exception {
        // TODO: 实现资源清理测试
        // 1. 验证 dockerClient.removeContainerCmd() 在 finally 块调用
        // 2. 验证即使异常也会清理资源
        // 3. 验证 workspaceManager.cleanup() 被调用
    }

    // ---- 辅助方法 ----

    private ControlApiClient.ClaimedJob createClaimedJob() {
        JobSpec jobSpec = createJobSpec();
        // ClaimedJob 是 public 字段 DTO（Jackson 反序列化形态），无带参构造
        ControlApiClient.ClaimedJob job = new ControlApiClient.ClaimedJob();
        job.jobId = UUID.randomUUID().toString();
        job.leaseEpoch = 0L;
        job.jobSpec = jobSpec;
        return job;
    }

    private JobSpec createJobSpec() {
        JobSpec.ResourceLimits limits = new JobSpec.ResourceLimits(
            1_000_000_000L,
            512 * 1024 * 1024L,
            512 * 1024 * 1024L,
            100
        );

        return new JobSpec(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0L,
            "REVIEW_TOOL_CALL",
            new JobSpec.ImageRef(new Digest("a".repeat(64)), "ubuntu:22.04"),
            List.of("echo", "test"),
            new Digest("b".repeat(64)),
            List.of(),
            limits,
            300,
            JobSpec.NetworkPolicy.NONE
        );
    }
}
