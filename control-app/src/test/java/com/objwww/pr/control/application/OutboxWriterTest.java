package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.repository.OutboxCommandRepository;
import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.FenceMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxCommand;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.RemoteIdentityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutboxWriter 装配逻辑：命令字段（PENDING/CURRENT_EPOCH/§6.3 remote_identity_type 映射）、
 * 每条命令独立领 (sequence, epoch)、依赖边（REQUIRE_CONFIRMED）、payload 落 CAS + 登记。
 */
class OutboxWriterTest {

    private static final UUID SUBJECT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID REVISION_ID = UUID.randomUUID();
    private static final String AGGREGATE_KEY = "pr:12345#7";

    private InMemoryStores.Subjects subjects;
    private InMemoryStores.OutboxCommands outbox;
    private InMemoryStores.Cas cas;
    private InMemoryStores.Artifacts artifacts;
    private OutboxWriter writer;

    @BeforeEach
    void setUp() {
        subjects = new InMemoryStores.Subjects();
        Instant now = Instant.now();
        subjects.save(new PRSubject(SUBJECT_ID, 11L, 12345L, "org/repo", 7,
                PrSubjectState.OPEN, false, false, null, "policy-v1",
                0, 1, 0, null, now, 0, 0, now, now));
        outbox = new InMemoryStores.OutboxCommands();
        cas = new InMemoryStores.Cas();
        artifacts = new InMemoryStores.Artifacts();
        SequenceAllocator allocator = new InMemoryStores.Sequences(subjects);
        writer = new OutboxWriter(outbox, allocator, cas, artifacts);
    }

    private PublicationRequest request(OperationId operationId, CommandType type,
                                       String payload, List<PublicationRequest.DependencyEdge> deps) {
        return new PublicationRequest(operationId, SUBJECT_ID, RUN_ID, REVISION_ID,
                AGGREGATE_KEY, type, "policy-v1",
                payload.getBytes(StandardCharsets.UTF_8), deps);
    }

    @Test
    void assemblesPendingCommandWithDerivedFields() {
        OperationId opId = OperationId.random();
        OutboxCommand cmd = writer.requestPublication(
                request(opId, CommandType.CREATE_CHECK, "{\"name\":\"ai-review\"}", List.of()));

        assertThat(cmd.operationId()).isEqualTo(opId);
        assertThat(cmd.state()).isEqualTo(OutboxState.PENDING);
        assertThat(cmd.fenceMode()).isEqualTo(FenceMode.CURRENT_EPOCH); // 默认 CURRENT_EPOCH（I6）
        assertThat(cmd.remoteIdentityType()).isEqualTo(RemoteIdentityType.EXTERNAL_ID); // §6.3
        assertThat(cmd.commandType()).isEqualTo(CommandType.CREATE_CHECK);
        assertThat(cmd.aggregateKey()).isEqualTo(AGGREGATE_KEY);
        assertThat(cmd.aggregateSequence()).isEqualTo(1);
        assertThat(cmd.publicationEpoch()).isEqualTo(0);
        assertThat(cmd.policyVersion()).isEqualTo("policy-v1");
        // payload 落 CAS + 登记，digest/hash 同源（M0：CAS 内容即 payload 正文）
        assertThat(cas.exists(cmd.payloadArtifactDigest())).isTrue();
        assertThat(cmd.payloadHash()).isEqualTo(cmd.payloadArtifactDigest());
        assertThat(artifacts.all()).hasSize(1);
        assertThat(artifacts.all().get(0).artifactType().name()).isEqualTo("REVIEW_PAYLOAD");
    }

    @Test
    void eachCommandAllocatesItsOwnSequence() {
        // §6.1 T2：每条命令各领一次 (sequence, epoch)
        OutboxCommand create = writer.requestPublication(
                request(OperationId.random(), CommandType.CREATE_CHECK, "{}", List.of()));
        OutboxCommand publish = writer.requestPublication(
                request(OperationId.random(), CommandType.PUBLISH_REVIEW, "{}",
                        List.of(PublicationRequest.DependencyEdge.requireConfirmed(create.operationId()))));

        assertThat(create.aggregateSequence()).isEqualTo(1);
        assertThat(publish.aggregateSequence()).isEqualTo(2);
        assertThat(publish.publicationEpoch()).isEqualTo(create.publicationEpoch());
    }

    @Test
    void publishReviewDependsOnCreateCheckWithRequireConfirmed() {
        OutboxCommand create = writer.requestPublication(
                request(OperationId.random(), CommandType.CREATE_CHECK, "{}", List.of()));
        OutboxCommand publish = writer.requestPublication(
                request(OperationId.random(), CommandType.PUBLISH_REVIEW, "{}",
                        List.of(PublicationRequest.DependencyEdge.requireConfirmed(create.operationId()))));

        assertThat(publish.remoteIdentityType()).isEqualTo(RemoteIdentityType.REVIEW_MARKER);
        List<InMemoryStores.OutboxCommands.DependencyRow> deps = outbox.dependencies();
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).operationId()).isEqualTo(publish.operationId());
        assertThat(deps.get(0).dependsOn()).isEqualTo(create.operationId());
        assertThat(deps.get(0).mode()).isEqualTo(DependencyMode.REQUIRE_CONFIRMED);
    }

    @Test
    void remoteIdentityTypeMappingCoversAllCommandTypes() {
        assertThat(OutboxWriter.remoteIdentityTypeOf(CommandType.CREATE_CHECK))
                .isEqualTo(RemoteIdentityType.EXTERNAL_ID);
        assertThat(OutboxWriter.remoteIdentityTypeOf(CommandType.UPDATE_CHECK))
                .isEqualTo(RemoteIdentityType.CHECK_RUN_ID);
        assertThat(OutboxWriter.remoteIdentityTypeOf(CommandType.PUBLISH_REVIEW))
                .isEqualTo(RemoteIdentityType.REVIEW_MARKER);
    }

    @Test
    void sequencePicksUpEpochAfterBump() {
        // 换届后 epoch+1，之后领到的命令携带新 epoch（fence 有效性前提，v2.2 §3-3）
        subjects.switchRevisionAndBumpEpoch(SUBJECT_ID, UUID.randomUUID(), "policy-v2", Instant.now());

        OutboxCommand cmd = writer.requestPublication(
                request(OperationId.random(), CommandType.CREATE_CHECK, "{}", List.of()));

        assertThat(cmd.publicationEpoch()).isEqualTo(1);
    }

    @Test
    void duplicateOperationIdRejectedByStore() {
        // operation_id 主键 = 幂等兜底（DB 层唯一约束；内存假实现模拟冲突）
        OperationId opId = OperationId.random();
        writer.requestPublication(request(opId, CommandType.CREATE_CHECK, "{}", List.of()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        writer.requestPublication(request(opId, CommandType.CREATE_CHECK, "{}", List.of())))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void portDeclaresNoStateMutation() {
        // 结构约束自查：Control 侧 outbox 端口只有 insert 两个方法（I10/AFT-06 的应用层镜像）
        assertThat(OutboxCommandRepository.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("insert", "insertDependency");
    }
}
