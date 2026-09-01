package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OutboxState;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** DISPATCHED 修复单的恢复投影器；不重发命令、不直接触网。 */
public final class RepairOutcomeProjector {
    private final PublicationStore store;
    private final int limit;
    private final long idleSleepMs;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public RepairOutcomeProjector(PublicationStore store, int limit, long idleSleepMs) {
        this.store = Objects.requireNonNull(store); this.limit = limit; this.idleSleepMs = idleSleepMs;
    }

    public int runOnce() {
        int count = 0;
        for (var target : store.findRepairOutcomes(limit)) {
            String state; ExecutionEventType type; String error = null;
            if (target.commandState() == OutboxState.CONFIRMED) {
                state = "REPAIRED"; type = ExecutionEventType.REPAIR_REPAIRED;
            } else if (target.commandState() == OutboxState.SUPERSEDED) {
                state = "EXPIRED"; type = ExecutionEventType.REPAIR_EXPIRED; error = "COMMAND_SUPERSEDED";
            } else if (target.commandState() == OutboxState.FAILED_TERMINAL
                    || target.commandState() == OutboxState.MANUAL) {
                state = "FAILED_TERMINAL"; type = ExecutionEventType.REPAIR_FAILED;
                error = "COMMAND_" + target.commandState();
            } else continue;
            ExecutionEvent event = new ExecutionEvent(UUID.randomUUID(), target.repairRunId(),
                    target.prRevisionId(), null, null, type, 1, null, target.repairRunId(),
                    "publisher-app", Map.of("repair_request_id", target.requestId().toString(),
                    "repair_operation_id", target.operationId().toString()), Instant.now());
            if (store.projectRepairOutcome(target.requestId(), state, error, event)) count++;
        }
        return count;
    }

    public void start() { if (running.compareAndSet(false, true)) thread = Thread.ofVirtual().start(this::loop); }
    public void stop() { running.set(false); if (thread != null) thread.interrupt(); }
    private void loop() { while (running.get()) try { if (runOnce()==0) Thread.sleep(idleSleepMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        catch (Exception ignored) { } }
}
