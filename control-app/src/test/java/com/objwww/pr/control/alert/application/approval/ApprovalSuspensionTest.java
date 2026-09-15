package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * durable suspension 单测（PC-C3，§2.10）：挂起/恢复生命周期、human_wait = wall
 * clock 差（双时钟：人的等待单列不进系统耗时）、豁免谓词接线。
 */
class ApprovalSuspensionTest {

    private static final Instant T0 = Instant.parse("2026-09-16T03:00:00Z");

    record EventRow(UUID runId, String type, String payload) {
    }

    static final class FakeEvents implements RcaEventAppender {
        final List<EventRow> rows = new ArrayList<>();

        @Override
        public long append(UUID runId, EventDraft draft) {
            rows.add(new EventRow(runId, draft.eventType(), draft.payloadJson()));
            return rows.size();
        }

        @Override
        public long appendIndependent(UUID runId, EventDraft draft) {
            return append(runId, draft);
        }
    }

    static final class DirectTx implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }

    static final class FakeSuspensions implements SuspensionStore {
        record Row(UUID id, UUID runId, UUID requestId, String state, Instant suspendedAt,
                Instant resumedAt) {
        }
        final Map<UUID, Row> rows = new HashMap<>();

        @Override
        public UUID insertSuspension(UUID runId, UUID requestId, Instant at) {
            UUID id = UUID.randomUUID();
            rows.put(id, new Row(id, runId, requestId, "SUSPENDED", at, null));
            return id;
        }

        @Override
        public boolean resumeSuspension(UUID runId, Instant at) {
            for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
                Row row = entry.getValue();
                if (row.runId().equals(runId) && "SUSPENDED".equals(row.state())) {
                    rows.put(entry.getKey(), new Row(row.id(), row.runId(), row.requestId(),
                            "RESUMED", row.suspendedAt(), at));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean hasActiveSuspension(UUID runId) {
            return rows.values().stream().anyMatch(r -> r.runId().equals(runId)
                    && "SUSPENDED".equals(r.state()));
        }

        @Override
        public Optional<SuspensionView> latest(UUID runId) {
            return Optional.empty();
        }

        @Override
        public SuspensionStats stats() {
            long total = rows.size();
            long active = rows.values().stream()
                    .filter(r -> "SUSPENDED".equals(r.state())).count();
            double wait = rows.values().stream()
                    .filter(r -> r.resumedAt() != null)
                    .mapToLong(r -> r.resumedAt().getEpochSecond() - r.suspendedAt().getEpochSecond())
                    .sum();
            return new SuspensionStats(total, active, wait);
        }
    }

    @Test
    void pcS01_挂起恢复全生命周期_humanWait单列() {
        UUID runId = UUID.randomUUID();
        FakeSuspensions store = new FakeSuspensions();
        FakeEvents events = new FakeEvents();
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        var service = new ApprovalSuspensionService(store, events, new DirectTx(), clock);

        UUID id = service.suspend(runId, UUID.randomUUID());
        assertThat(store.hasActiveSuspension(runId)).isTrue();
        assertThat(events.rows.get(0).type()).isEqualTo("APPROVAL_SUSPENDED");

        // 挂起 97 秒后恢复（wall clock 差——诚实的用户等待）
        Clock later = Clock.fixed(T0.plusSeconds(97), ZoneOffset.UTC);
        var laterService = new ApprovalSuspensionService(store, events, new DirectTx(), later);
        assertThat(laterService.resume(runId)).isTrue();
        assertThat(laterService.resume(runId)).isFalse(); // 已恢复，CAS 拒
        assertThat(store.hasActiveSuspension(runId)).isFalse();
        assertThat(store.stats().humanWaitSeconds()).isEqualTo(97.0);
        assertThat(store.stats().active()).isZero();
        assertThat(events.rows.stream().map(EventRow::type)).contains("APPROVAL_RESUMED");
    }

    @Test
    void pcS02_豁免谓词_挂起true_恢复后false() {
        UUID runId = UUID.randomUUID();
        FakeSuspensions store = new FakeSuspensions();
        var service = new ApprovalSuspensionService(store, new FakeEvents(), new DirectTx(),
                Clock.fixed(T0, ZoneOffset.UTC));
        java.util.function.Predicate<UUID> gate = service::hasActiveSuspension;
        assertThat(gate.test(runId)).isFalse();
        service.suspend(runId, UUID.randomUUID());
        assertThat(gate.test(runId)).isTrue(); // RunReconciler 据此豁免 AUTO_EXPIRE/LIVE_BUT_STUCK
    }
}
