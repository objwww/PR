package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Intake 最小版（application，§3 IntakeService）：落最小接收记录 → 异步派发 T0/T1，
 * HTTP 线程立即返回（19 阶段表 #1）。B-3：M0 不做 inbox 去重——同一 delivery 重投
 * 由 run_key 唯一约束兜底（DuplicateKeyException 捕获后幂等返回已有 Run，ST-05）；
 * 完整 inbox 语义（乱序/半截处理）M1 补（P1）。
 *
 * <p>显式 Executor 注入而非 @Async：测试可换直连执行器，派发时序可控。
 * 异步任务内的失败只记日志（M0 无 inbox 重放源，B-3/P1 诚实边界）。
 */
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final SnapshotService snapshotService;
    private final ReviewOrchestrator orchestrator;
    private final ArtifactStore artifactStore;
    private final ArtifactRepository artifactRepository;
    private final Executor intakeExecutor;
    private final String policyVersion;
    private final String promptVersion;
    private final String toolsetVersion;

    public IntakeService(SnapshotService snapshotService, ReviewOrchestrator orchestrator,
                         ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                         Executor intakeExecutor,
                         String policyVersion, String promptVersion, String toolsetVersion) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.intakeExecutor = Objects.requireNonNull(intakeExecutor);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.promptVersion = Objects.requireNonNull(promptVersion);
        this.toolsetVersion = Objects.requireNonNull(toolsetVersion);
    }

    /** 控制器入口：立即返回，实际派发在 intakeExecutor 上异步执行 */
    public void accept(PullRequestEvent event, byte[] rawPayload) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(rawPayload, "rawPayload");
        intakeExecutor.execute(() -> dispatchSafely(event, rawPayload));
    }

    /** 异步任务本体（包私有便于测试用直连执行器驱动） */
    void dispatchSafely(PullRequestEvent event, byte[] rawPayload) {
        try {
            dispatch(event, rawPayload);
        } catch (Exception e) {
            // M0 无 inbox 重放源：失败只记日志（B-3/P1 边界）；token/密钥不入日志
            log.error("intake 派发失败 delivery={} repo={}#{}", event.deliveryId(),
                    event.repositoryFullName(), event.prNumber(), e);
        }
    }

    void dispatch(PullRequestEvent event, byte[] rawPayload) {
        // 0) 落最小接收记录：webhook 原文 → CAS + artifact 登记（WEBHOOK_PAYLOAD）
        Digest payloadDigest = Digest.sha256Of(new String(rawPayload, StandardCharsets.UTF_8));
        String path = artifactStore.putIfAbsent(payloadDigest, rawPayload);
        artifactRepository.register(new ArtifactRecord(payloadDigest, ArtifactType.WEBHOOK_PAYLOAD,
                rawPayload.length, path, Instant.now()));

        // 1) T0（事务外）：快照 + diff digest（网络 I/O 不进 DB 事务，评审修正 #3）
        SnapshotService.SnapshotOutcome snapshot = snapshotService.prepare(
                event.installationId(), event.repositoryFullName(), event.baseSha(), event.headSha());

        // 2) T1（事务内）：建 Run；DuplicateKeyException = run_key 重投兜底（B-3）
        IntakeCommand cmd = toCommand(event, snapshot);
        try {
            orchestrator.runIntake(cmd);
        } catch (DuplicateKeyException e) {
            Optional<ReviewRun> existing = orchestrator.findExistingRun(cmd);
            if (existing.isPresent()) {
                log.info("webhook 重投幂等返回 delivery={} run={}", event.deliveryId(), existing.get().getId());
                return;
            }
            // 并发首建竞态（同 PR 两事件同时进）：对方事务提交后重试一次；再冲突即真异常
            log.warn("intake 唯一约束冲突但无既有 Run，重试一次 delivery={}", event.deliveryId());
            orchestrator.runIntake(cmd);
        }
    }

    private IntakeCommand toCommand(PullRequestEvent event, SnapshotService.SnapshotOutcome snapshot) {
        return new IntakeCommand(
                event.installationId(), event.repositoryId(), event.repositoryFullName(),
                event.prNumber(),
                "closed".equalsIgnoreCase(event.prState()) ? PrSubjectState.CLOSED : PrSubjectState.OPEN,
                event.draft(), event.merged(),
                event.headSha(), event.baseRef(), event.baseSha(), null,
                snapshot.diffDigest(), snapshot.sourceSnapshotDigest(),
                policyVersion, promptVersion, toolsetVersion,
                event.deliveryId());
    }
}
