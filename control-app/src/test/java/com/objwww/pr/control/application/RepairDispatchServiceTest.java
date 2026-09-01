package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.model.RunMode;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import com.objwww.pr.control.domain.service.SequenceLease;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import com.objwww.pr.shared.RepairRequestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** REPAIR Run 铸造 + 世代 gate + 失败收口（§4.3；RM2-10 允许 REPAIR Run 发布）。 */
class RepairDispatchServiceTest {

    private InMemoryStores.RepairRequests requests;
    private InMemoryStores.Runs runs;
    private InMemoryStores.OutboxCommands outboxCommands;
    private InMemoryStores.Events events;
    private RepairDispatchService dispatcher;

    @BeforeEach
    void setUp() {
        requests = new InMemoryStores.RepairRequests();
        runs = new InMemoryStores.Runs(new InMemoryStores.Revisions());
        outboxCommands = new InMemoryStores.OutboxCommands();
        events = new InMemoryStores.Events();
        OutboxWriter outbox = new OutboxWriter(outboxCommands,
                subject -> new SequenceLease(1, 0), new InMemoryStores.Cas(),
                new InMemoryStores.Artifacts());
        dispatcher = new RepairDispatchService(requests, runs, outbox, new ExecutionLedger(events));
    }

    private RepairCandidate candidate(boolean generationCurrent) {
        UUID revision = UUID.randomUUID();
        return new RepairCandidate(UUID.randomUUID(), RepairPolicyTier.AUTO,
                RepairRequestState.PENDING, 0, 5, UUID.randomUUID(),
                PublicationResourceType.CHECK_RUN, UUID.randomUUID(), revision,
                generationCurrent ? revision : UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CommandType.CREATE_CHECK, "pr:1#1", "policy-v1",
                Digest.sha256Of("p"), Digest.sha256Of("b"));
    }

    private RepairCommandFactory.Prepared prepared() {
        return new RepairCommandFactory.Prepared(OperationId.random(), CommandType.CREATE_CHECK,
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dispatchCreatesPublishableRepairRun() {
        // RM2-10：REPAIR Run publisherDisabled=false——其存在意义即铸出并发布 repair 命令
        RepairCandidate c = candidate(true);
        requests.add(c);
        RepairCommandFactory.Prepared p = prepared();

        boolean dispatched = dispatcher.dispatch(c.requestId(), p);

        assertThat(dispatched).isTrue();
        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.DISPATCHED);
        ReviewRun run = runs.findById(requests.repairRunIdOf(c.requestId())).orElseThrow();
        assertThat(run.getRunMode()).isEqualTo(RunMode.REPAIR);
        assertThat(run.isPublisherDisabled()).isFalse();
        assertThat(outboxCommands.all()).hasSize(1);
        assertThat(outboxCommands.all().get(0).operationId()).isEqualTo(p.operationId());
        // depends_on = 原命令（REQUIRE_CONFIRMED）
        assertThat(outboxCommands.dependencies()).hasSize(1);
        assertThat(outboxCommands.dependencies().get(0).dependsOn().value())
                .isEqualTo(c.originalOperationId());
        assertThat(events.all()).anySatisfy(e ->
                assertThat(e.eventType()).isEqualTo(ExecutionEventType.REPAIR_DISPATCHED));
    }

    @Test
    void staleGenerationExpiresWithoutCommand() {
        // 世代 gate：原 revision 已不是当前世代 → EXPIRED，绝不向旧世代补写
        RepairCandidate c = candidate(false);
        requests.add(c);

        boolean handled = dispatcher.dispatch(c.requestId(), prepared());

        assertThat(handled).isTrue();
        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.EXPIRED);
        assertThat(outboxCommands.all()).isEmpty();
        assertThat(events.all()).anySatisfy(e ->
                assertThat(e.eventType()).isEqualTo(ExecutionEventType.REPAIR_EXPIRED));
    }

    @Test
    void nonRetryableFailMarksTerminalAndEmitsEvent() {
        RepairCandidate c = candidate(true);
        requests.add(c);

        dispatcher.fail(c, false, "DESIRED_PAYLOAD_MISSING");

        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.FAILED_TERMINAL);
        assertThat(requests.lastErrorOf(c.requestId())).isEqualTo("DESIRED_PAYLOAD_MISSING");
        assertThat(events.all()).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(ExecutionEventType.REPAIR_FAILED);
            assertThat(e.payload()).containsEntry("reason", "DESIRED_PAYLOAD_MISSING");
        });
    }
}
