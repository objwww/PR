package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.model.RepairOutcomeTarget;
import com.objwww.pr.publisher.fakes.FakePublicationStore;
import com.objwww.pr.shared.OutboxState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepairOutcomeProjectorTest {

    @Test
    void projectsAllTerminalCommandOutcomesIdempotently() {
        FakePublicationStore store = new FakePublicationStore();
        UUID run = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        var repaired = target(OutboxState.CONFIRMED, run, revision, resource);
        var failed = target(OutboxState.FAILED_TERMINAL, run, revision, resource);
        var expired = target(OutboxState.SUPERSEDED, run, revision, resource);
        store.repairOutcomes.add(repaired);
        store.repairOutcomes.add(failed);
        store.repairOutcomes.add(expired);
        RepairOutcomeProjector projector = new RepairOutcomeProjector(store, 10, 0);

        assertThat(projector.runOnce()).isEqualTo(3);
        assertThat(store.projectedRepairStates.get(repaired.requestId())).isEqualTo("REPAIRED");
        assertThat(store.projectedRepairStates.get(failed.requestId())).isEqualTo("FAILED_TERMINAL");
        assertThat(store.projectedRepairStates.get(expired.requestId())).isEqualTo("EXPIRED");
        assertThat(projector.runOnce()).isZero();
    }

    private static RepairOutcomeTarget target(OutboxState state, UUID run, UUID revision, UUID resource) {
        return new RepairOutcomeTarget(UUID.randomUUID(), UUID.randomUUID(), state,
                run, revision, resource);
    }
}
