package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.domain.service.SequenceLease;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.FenceMode;
import com.objwww.pr.shared.OutboxCommand;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.RemoteIdentityType;

import java.time.Instant;
import java.util.Objects;

/**
 * Outbox 命令装配器（application，§3 OutboxWriter）：T2 事务内原子领 (sequence, epoch)、
 * payload 落 CAS、插类型化 PENDING 命令 + 依赖边。
 *
 * <p>纪律（方案 §3.1/§6.2）：
 * <ul>
 *   <li>只在 T2 事务内被调用——sequence/epoch 的行锁必须由调用方事务持有到 COMMIT；</li>
 *   <li>不直接调 GitHub、不 UPDATE outbox（Control 只有 INSERT 权限，I10）；</li>
 *   <li>fence_mode 默认 CURRENT_EPOCH（I6）；remote_identity_type 按 §6.3 表由命令类型推导，
 *       调用方无从填错。</li>
 * </ul>
 */
public class OutboxWriter {

    private final OutboxCommandRepository outboxRepository;
    private final SequenceAllocator sequenceAllocator;
    private final ArtifactStore artifactStore;
    private final ArtifactRepository artifactRepository;

    public OutboxWriter(OutboxCommandRepository outboxRepository, SequenceAllocator sequenceAllocator,
                        ArtifactStore artifactStore, ArtifactRepository artifactRepository) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository);
        this.sequenceAllocator = Objects.requireNonNull(sequenceAllocator);
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
    }

    /**
     * 装配并插入一条 PENDING 命令及其依赖边。
     * 每条命令独立领一次 (sequence, epoch)（§6.1 T2：行锁下同 PR 严格单调，I8）。
     */
    public OutboxCommand requestPublication(PublicationRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = Instant.now();

        // 1) 原子领 (sequence, epoch)——行锁由调用方事务持有
        SequenceLease lease = sequenceAllocator.allocate(request.prSubjectId());

        // 2) payload 落 CAS + artifact 登记；payload_hash 为 payload 正文的 SHA-256
        //    （M0：CAS 内容即 payload 正文，两列同源；分列保留给 payload 为打包产物时的差异场景）
        Digest payloadHash = Digest.sha256Of(new String(request.payload(), java.nio.charset.StandardCharsets.UTF_8));
        String storagePath = artifactStore.putIfAbsent(payloadHash, request.payload());
        artifactRepository.register(new ArtifactRecord(payloadHash, ArtifactType.REVIEW_PAYLOAD,
                request.payload().length, storagePath, now));

        // 3) 装配类型化命令：PENDING + CURRENT_EPOCH + §6.3 远端身份类型
        OutboxCommand command = new OutboxCommand(
                request.operationId(),
                request.prSubjectId(),
                request.reviewRunId(),
                request.prRevisionId(),
                request.aggregateKey(),
                lease.sequence(),
                lease.publicationEpoch(),
                FenceMode.CURRENT_EPOCH,
                request.commandType(),
                OutboxState.PENDING,
                request.policyVersion(),
                payloadHash,
                payloadHash,
                remoteIdentityTypeOf(request.commandType()));
        outboxRepository.insert(command);

        // 4) 依赖边（如 PUBLISH_REVIEW 依赖 CREATE_CHECK，REQUIRE_CONFIRMED）
        for (PublicationRequest.DependencyEdge edge : request.dependencies()) {
            outboxRepository.insertDependency(
                    request.operationId(), edge.dependsOn(), edge.mode(), now);
        }
        return command;
    }

    /** §6.3 表：remote_identity_type 由命令类型唯一推导，不给调用方自由填 */
    static RemoteIdentityType remoteIdentityTypeOf(CommandType commandType) {
        return switch (commandType) {
            case CREATE_CHECK -> RemoteIdentityType.EXTERNAL_ID;
            case UPDATE_CHECK -> RemoteIdentityType.CHECK_RUN_ID;
            case PUBLISH_REVIEW -> RemoteIdentityType.REVIEW_MARKER;
        };
    }
}
