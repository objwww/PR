package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.RcaEventReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EventQueryService 单测（M5-13）：after_seq 游标读取、limit 钳制、seq 缺口/超窗
 * gap 判定（→ 客户端 resync）、payload 白名单消毒面。
 */
class EventQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    void readsEventsAfterCursorWithLatestSeqAndNoGap() {
        InMemoryEvents events = new InMemoryEvents(List.of(
                row(1, "RUN_CREATED", "{\"run_id\":\"r\"}"),
                row(2, "TASK_STATE_CHANGED", "{\"task_id\":\"t1\",\"summary\":\"指标完成\"}"),
                row(3, "RUN_FINISHED", "{\"summary\":\"done\"}")));
        EventQueryService service = new EventQueryService(events, new EventPayloadSanitizer(200));

        EventQueryService.EventPage page = service.events(UUID.randomUUID(), 0, 50);

        assertThat(page.gap()).isFalse();
        assertThat(page.latestSeq()).isEqualTo(3);
        assertThat(page.events()).hasSize(3);
        assertThat(page.events().get(1).taskId()).isEqualTo("t1");
        assertThat(page.events().get(1).summary()).isEqualTo("指标完成");
    }

    @Test
    void cursorReturnsOnlyRowsAfterSeq() {
        InMemoryEvents events = new InMemoryEvents(List.of(
                row(1, "A", "{}"), row(2, "B", "{}"), row(3, "C", "{}")));
        EventQueryService service = new EventQueryService(events, new EventPayloadSanitizer(200));

        EventQueryService.EventPage page = service.events(UUID.randomUUID(), 2, 50);

        assertThat(page.events()).hasSize(1);
        assertThat(page.events().get(0).seq()).isEqualTo(3);
    }

    @Test
    void limitIsClampedToAcceptedBounds() {
        List<RcaEventReader.EventRow> rows = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            rows.add(row(i, "E", "{}"));
        }
        EventQueryService service = new EventQueryService(
                new InMemoryEvents(rows), new EventPayloadSanitizer(200));

        // limit<1 → 钳 1；limit>200 → 钳 200
        assertThat(service.events(UUID.randomUUID(), 0, 0).events()).hasSize(1);
        assertThat(service.events(UUID.randomUUID(), 0, 999).events()).hasSize(10);
    }

    @Test
    void detectsHoleBetweenCursorAndFirstRowAsGap() {
        // 客户端游标在 1，但下一条从 3 开始（历史被截/异常）→ gap=true 推 resync
        InMemoryEvents events = new InMemoryEvents(List.of(row(3, "C", "{}"), row(4, "D", "{}"))) {
            @Override
            public OptionalLong latestSeq(UUID runId) {
                return OptionalLong.of(4);
            }
        };
        EventQueryService service = new EventQueryService(events, new EventPayloadSanitizer(200));

        EventQueryService.EventPage page = service.events(UUID.randomUUID(), 1, 50);

        assertThat(page.gap()).isTrue();
        assertThat(page.latestSeq()).isEqualTo(4);
    }

    @Test
    void clientAheadOfLatestIsGap() {
        InMemoryEvents events = new InMemoryEvents(List.of());
        EventQueryService service = new EventQueryService(events, new EventPayloadSanitizer(200));

        EventQueryService.EventPage page = service.events(UUID.randomUUID(), 9, 50);

        assertThat(page.gap()).as("after_seq 超过 latest_seq = 超窗 → resync").isTrue();
        assertThat(page.events()).isEmpty();
    }

    // ------------------------------------------------------------------

    private RcaEventReader.EventRow row(long seq, String type, String payload) {
        return new RcaEventReader.EventRow(seq, type, payload, NOW);
    }

    /** seq 连续内存假（真实连续性由 PG uq(run_id,seq)+appender 保证） */
    static class InMemoryEvents implements RcaEventReader {
        private final List<RcaEventReader.EventRow> rows;

        InMemoryEvents(List<RcaEventReader.EventRow> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public List<RcaEventReader.EventRow> readAfter(UUID runId, long afterSeq, int limit) {
            return rows.stream().filter(r -> r.seq() > afterSeq).limit(limit).toList();
        }

        @Override
        public OptionalLong latestSeq(UUID runId) {
            return rows.isEmpty()
                    ? OptionalLong.empty()
                    : OptionalLong.of(rows.get(rows.size() - 1).seq());
        }
    }
}
