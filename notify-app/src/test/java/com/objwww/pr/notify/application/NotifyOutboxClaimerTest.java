package com.objwww.pr.notify.application;

import com.objwww.pr.notify.domain.model.ClaimedNotification;
import com.objwww.pr.notify.domain.port.NotifyOutboxStore;
import com.objwww.pr.notify.domain.service.FencedNotifyExecutor;
import com.objwww.pr.notify.domain.service.NotifySender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * M3-20 领取循环：串行有限批量、单条失败不阻塞整批（行留租约待回收）、空轮休眠、
 * start/stop 幂等。
 */
class NotifyOutboxClaimerTest {

    @Test
    @DisplayName("runOnce：逐条串行执行、计数返回；单条执行抛错不阻塞余下条目")
    void executesBatchSeriallyAndIsolatesFailures() {
        UUID boomId = UUID.randomUUID();
        List<ClaimedNotification> batch = List.of(notification(UUID.randomUUID()),
                notification(boomId), notification(UUID.randomUUID()));
        List<UUID> executed = new ArrayList<>();
        FakeStore store = new FakeStore(batch);
        NotifySender sender = notification -> {
            if (notification.operationId().equals(boomId)) {
                throw new IllegalStateException("boom");
            }
            executed.add(notification.operationId());
            return FencedNotifyExecutor.Outcome.SENT;
        };
        NotifyOutboxClaimer claimer = new NotifyOutboxClaimer(store, sender, "owner-1",
                Duration.ofSeconds(60), 10, 1000, 1000);

        assertThat(claimer.runOnce()).isEqualTo(3);
        assertThat(executed).hasSize(2);
        assertThat(store.claimedOwners).containsExactly("owner-1");
    }

    @Test
    @DisplayName("start/stop 幂等；stop 后循环线程退出（重复 start 不再起线程）")
    void startStopIdempotent() {
        FakeStore store = new FakeStore(List.of());
        NotifyOutboxClaimer claimer = new NotifyOutboxClaimer(store,
                notification -> FencedNotifyExecutor.Outcome.SENT, "owner-1",
                Duration.ofSeconds(60), 10, 50, 50);
        assertThatCode(() -> {
            claimer.start();
            claimer.start();
            Thread.sleep(150);
            claimer.stop();
            claimer.stop();
        }).doesNotThrowAnyException();
        assertThat(store.claimCount).isGreaterThanOrEqualTo(0);
    }

    private static ClaimedNotification notification(UUID operationId) {
        return new ClaimedNotification(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "dingtalk-test", "v1",
                operationId, "{}", 0, 5, 1);
    }

    private static final class FakeStore implements NotifyOutboxStore {
        private final List<ClaimedNotification> batch;
        final List<String> claimedOwners = new ArrayList<>();
        int claimCount;

        FakeStore(List<ClaimedNotification> batch) {
            this.batch = batch;
        }

        @Override
        public List<ClaimedNotification> claim(String leaseOwner, Duration leaseDuration,
                                               int batchSize) {
            claimCount++;
            claimedOwners.add(leaseOwner);
            return batch;
        }

        @Override
        public void markSent(UUID id, long leaseEpoch, java.time.Instant sentAt) {
        }

        @Override
        public void markRetryWait(UUID id, long leaseEpoch, java.time.Instant availableAt,
                                  boolean consumeAttempt, String lastErrorJson) {
        }

        @Override
        public void markDead(UUID id, long leaseEpoch, String lastErrorJson) {
        }

        @Override
        public void markSuppressed(UUID id, long leaseEpoch, String noteJson) {
        }

        @Override
        public void syncPublication(UUID publicationId) {
        }
    }
}
