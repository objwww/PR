package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PA-A2（V112）事件哈希链单测：append 即链写、verifyChain 全链重算、篡改可证、
 * legacy 段边界语义（NULL 尾后起新段）。口径：tamper-evident under the assumed
 * DB write boundary（R9）。
 */
class EventHashChainTest {

    private final AlertInMemoryStores.RcaEventLog events = new AlertInMemoryStores.RcaEventLog();

    private UUID append(UUID runId, String type) {
        UUID eventId = UUID.randomUUID();
        events.append(runId, new RcaEventAppender.EventDraft(eventId, type, "{\"k\":\"v\"}"));
        return eventId;
    }

    @Test
    void pa2_chainVerifiesAfterAppends() {
        UUID runId = UUID.randomUUID();
        append(runId, "E1");
        append(runId, "E2");
        append(runId, "E3");

        RcaEventAppender.ChainReport report = events.verifyChain(runId);

        assertThat(report.ok()).isTrue();
        assertThat(report.events()).isEqualTo(3);
        assertThat(report.verified()).isEqualTo(3);
        assertThat(report.brokenAtSeq()).isEqualTo(-1);
    }

    @Test
    void pa2_tamperedHashIsDetectedAtExactSeq() {
        UUID runId = UUID.randomUUID();
        append(runId, "E1");
        append(runId, "E2");
        append(runId, "E3");
        events.tamperLastHash(runId);

        RcaEventAppender.ChainReport report = events.verifyChain(runId);

        assertThat(report.ok()).isFalse();
        assertThat(report.brokenAtSeq()).isEqualTo(3);
        assertThat(report.verified()).isEqualTo(2);
    }

    @Test
    void pa2_runsWithEventsEnumeratedForDailyVerifyLoop() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        append(runA, "E1");
        append(runB, "E1");

        assertThat(events.runIdsWithEvents()).containsExactlyInAnyOrder(runA, runB);
        assertThat(new EventChainVerifyLoop(events, java.time.Duration.ofHours(24))
                .verifyOnce()).isZero();
    }

    @Test
    void pa2_emptyChainVerifiesOk() {
        assertThat(events.verifyChain(UUID.randomUUID()).ok()).isTrue();
        assertThat(events.verifyChain(UUID.randomUUID()).events()).isZero();
    }
}
