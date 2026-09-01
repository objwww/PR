package com.objwww.pr.control.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.service.ExecutionLedger;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import com.objwww.pr.control.domain.service.SequenceLease;
import com.objwww.pr.control.support.InMemoryStores;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import com.objwww.pr.shared.RepairRequestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RepairPlanner 失败分路（§4.3/EX-29）：CAS 确定性损坏 fail-closed 立即 FAILED_TERMINAL，
 * 坏 payload 立即终态，瞬时故障才走 RETRY_WAIT 退避。
 */
class RepairPlannerTest {

    private InMemoryStores.RepairRequests requests;
    private InMemoryStores.Cas cas;
    private InMemoryStores.Events events;
    private RepairDispatchService dispatcher;

    @BeforeEach
    void setUp() {
        requests = new InMemoryStores.RepairRequests();
        cas = new InMemoryStores.Cas();
        events = new InMemoryStores.Events();
        OutboxWriter outbox = new OutboxWriter(new InMemoryStores.OutboxCommands(),
                subject -> new SequenceLease(1, 0), cas, new InMemoryStores.Artifacts());
        dispatcher = new RepairDispatchService(requests,
                new InMemoryStores.Runs(new InMemoryStores.Revisions()),
                outbox, new ExecutionLedger(events));
    }

    private RepairPlanner planner(ArtifactStore store) {
        return new RepairPlanner(requests, store,
                new RepairCommandFactory(new ObjectMapper()), dispatcher, 10, 0);
    }

    private RepairCandidate candidate(Digest payloadHash, Digest basePayloadHash) {
        UUID revision = UUID.randomUUID();
        return new RepairCandidate(UUID.randomUUID(), RepairPolicyTier.AUTO,
                RepairRequestState.PENDING, 0, 5, UUID.randomUUID(),
                PublicationResourceType.CHECK_RUN, UUID.randomUUID(), revision, revision,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CommandType.CREATE_CHECK, "pr:1#1", "policy-v1", payloadHash, basePayloadHash);
    }

    @Test
    void missingCasPayloadFailsClosedTerminalImmediately() {
        // RM2-06：CAS 按 digest 寻址，get 返回 empty = 确定性损坏，不烧重试预算
        Digest missing = Digest.sha256Of("gone");
        RepairCandidate c = candidate(missing, missing);
        requests.add(c);

        int handled = planner(cas).runOnce();

        assertThat(handled).isEqualTo(1);
        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.FAILED_TERMINAL);
        assertThat(requests.attemptCountOf(c.requestId())).isZero();
        assertThat(requests.lastErrorOf(c.requestId())).isEqualTo("DESIRED_PAYLOAD_MISSING");
        assertThat(events.all()).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(ExecutionEventType.REPAIR_FAILED);
            assertThat(e.payload()).containsEntry("reason", "DESIRED_PAYLOAD_MISSING");
        });
    }

    @Test
    void badDesiredPayloadFailsTerminalImmediately() {
        Digest bad = Digest.sha256Of("bad");
        cas.putIfAbsent(bad, "not-json".getBytes(StandardCharsets.UTF_8));
        RepairCandidate c = candidate(bad, bad);
        requests.add(c);

        planner(cas).runOnce();

        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.FAILED_TERMINAL);
        assertThat(requests.lastErrorOf(c.requestId())).isEqualTo("BAD_DESIRED_PAYLOAD");
        assertThat(events.all()).anySatisfy(e ->
                assertThat(e.eventType()).isEqualTo(ExecutionEventType.REPAIR_FAILED));
    }

    @Test
    void transientCasFailureStaysRetryable() {
        // 瞬时 FS 故障（get 抛异常）分支不变：RETRY_WAIT + 退避，不进终态
        ArtifactStore flaky = new ArtifactStore() {
            @Override
            public String putIfAbsent(Digest digest, byte[] content) {
                return "mem/" + digest.value();
            }

            @Override
            public boolean exists(Digest digest) {
                return false;
            }

            @Override
            public Optional<byte[]> get(Digest digest) {
                throw new RuntimeException("fs down");
            }
        };
        RepairCandidate c = candidate(Digest.sha256Of("x"), Digest.sha256Of("y"));
        requests.add(c);

        planner(flaky).runOnce();

        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.RETRY_WAIT);
        assertThat(requests.attemptCountOf(c.requestId())).isEqualTo(1);
        assertThat(requests.lastErrorOf(c.requestId())).isEqualTo("PLANNER_TRANSIENT");
        assertThat(events.all()).noneMatch(e -> e.eventType() == ExecutionEventType.REPAIR_FAILED);
    }

    @Test
    void readyAutoRequestIsDispatched() {
        Digest payload = Digest.sha256Of("{\"name\":\"ai-review\"}");
        cas.putIfAbsent(payload, "{\"name\":\"ai-review\"}".getBytes(StandardCharsets.UTF_8));
        RepairCandidate c = candidate(payload, payload);
        requests.add(c);

        planner(cas).runOnce();

        assertThat(requests.stateOf(c.requestId())).isEqualTo(RepairRequestState.DISPATCHED);
        assertThat(requests.repairRunIdOf(c.requestId())).isNotNull();
    }
}
