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

/**
 * Intake 纯执行段（application，M1-T04 改造，方案 §3.1 IntakeService 行）。
 * M0 时自持 Executor 异步派发、失败只记日志（B-3/P1 诚实边界）；M1 起由
 * {@link InboxProcessor} 经 inbox 租约**同步**驱动——重试/退避/死信由 inbox 六态机接管，
 * 本类不再决定忽略事件、不再自持执行器、不再吞异常（失败上抛，由 Processor 按
 * RETRY_WAIT/DEAD_LETTER 回写，EX-11/CT-16）。
 *
 * <p>职责不变的部分：webhook 原文落 CAS（WEBHOOK_PAYLOAD 登记）仍在 dispatch 内
 * （输入现在来自 inbox.payload_raw）；run_key 唯一约束兜底重投/并发首建不变（B-3，ST-05）。
 */
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final SnapshotService snapshotService;
    private final ReviewOrchestrator orchestrator;
    private final ArtifactStore artifactStore;
    private final ArtifactRepository artifactRepository;
    private final String policyVersion;
    private final String promptVersion;
    private final String toolsetVersion;

    public IntakeService(SnapshotService snapshotService, ReviewOrchestrator orchestrator,
                         ArtifactStore artifactStore, ArtifactRepository artifactRepository,
                         String policyVersion, String promptVersion, String toolsetVersion) {
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.promptVersion = Objects.requireNonNull(promptVersion);
        this.toolsetVersion = Objects.requireNonNull(toolsetVersion);
    }

    /**
     * M0 派发语义原样（CAS 接收记录 → T0 → T1），由 InboxProcessor 持租约同步调用。
     * 异常一律上抛（Processor 据此退避/死信）；public 可见性同时供 IT 分步驱动
     * 模拟崩溃窗口（ST-17）。
     */
    public void dispatch(PullRequestEvent event, byte[] rawPayload) {
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
                event.deliveryId(), event.updatedAt());
    }
}
