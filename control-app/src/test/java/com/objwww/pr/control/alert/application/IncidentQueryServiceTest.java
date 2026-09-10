package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.AlertOverview;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.Facets;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentDetail;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentPage;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentRow;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentSummary;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.KeysetCursor;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.RunsStats;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.TrendBucket;
import com.objwww.pr.control.ops.application.OperatorQueryService;
import com.objwww.pr.control.ops.domain.model.CaseStatus;
import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import com.objwww.pr.control.ops.duty.domain.RotationMath;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IncidentQueryService 单测（UI-1；RunQueryServiceTest 同模式——假端口纯函数段）：
 * 游标编解码与校验、facet status 种子桶、summary 诚实 null 穿透、overview 跨域装配
 * （cases 口径复用 OperatorQueryService 真身 + 假仓储；趋势 24 桶补零）。
 */
class IncidentQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:30:00Z");
    private static final Instant SINCE = NOW.minus(java.time.Duration.ofHours(24));

    private final FakeReader reader = new FakeReader();
    private final FakeDutyStore dutyStore = new FakeDutyStore();
    private final FakeCaseRepository caseRepository = new FakeCaseRepository();
    private final IncidentQueryService service = new IncidentQueryService(reader, dutyStore,
            new OperatorQueryService(caseRepository, () -> NOW), () -> NOW);

    // ------------------------------------------------------------------ 列表 / 游标

    @Test
    void listEncodesKeysetCursorFromLastRowOnlyWhenHasMore() {
        Instant lastEvent = Instant.parse("2026-09-09T09:00:00Z");
        UUID id = UUID.randomUUID();
        reader.page = new IncidentPage(
                List.of(row(UUID.randomUUID(), lastEvent.plusSeconds(60)), row(id, lastEvent)),
                7, true);

        IncidentQueryService.IncidentListResponse out =
                service.list("FIRING", "critical", "order-arena", "oom", null, 50);

        assertThat(out.total()).isEqualTo(7);
        assertThat(out.nextCursor()).isEqualTo(lastEvent + "/" + id);
        // 过滤参数原样透传端口
        assertThat(reader.lastStatus).isEqualTo("FIRING");
        assertThat(reader.lastSeverity).isEqualTo("critical");
        assertThat(reader.lastService).isEqualTo("order-arena");
        assertThat(reader.lastQ).isEqualTo("oom");
        assertThat(reader.lastLimit).isEqualTo(50);
        assertThat(reader.lastCursor).isNull();
    }

    @Test
    void listWithoutMorePagesEmitsNullCursor() {
        reader.page = new IncidentPage(List.of(row(UUID.randomUUID(), NOW)), 1, false);
        assertThat(service.list(null, null, null, null, null, 50).nextCursor()).isNull();
    }

    @Test
    void listParsesIncomingCursorIntoStructuredKeyset() {
        Instant at = Instant.parse("2026-09-08T01:02:03Z");
        UUID id = UUID.randomUUID();
        reader.page = new IncidentPage(List.of(), 0, false);

        service.list(null, null, null, null, at + "/" + id, 50);

        assertThat(reader.lastCursor).isEqualTo(new KeysetCursor(at, id));
    }

    @Test
    void malformedCursorAndBadStatusAreRejected() {
        assertThatThrownBy(() -> service.list(null, null, null, null, "garbage", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null,
                "2026-09-08T01:02:03Z/not-a-uuid", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("firing", null, null, null, null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FIRING/RESOLVED");
    }

    // ------------------------------------------------------------------ facet / 统计条

    @Test
    void facetsSeedStatusBucketsAndPassThroughLabelFacets() {
        reader.facets = new Facets(Map.of("FIRING", 3L),
                new LinkedHashMap<>(Map.of("critical", 2L)),
                new LinkedHashMap<>(Map.of("order-arena", 3L)));

        IncidentQueryService.FacetsResponse out = service.facets(null, null, null);

        assertThat(out.status()).containsEntry("FIRING", 3L).containsEntry("RESOLVED", 0L);
        assertThat(out.severity()).containsExactly(Map.entry("critical", 2L));
        assertThat(out.service()).containsExactly(Map.entry("order-arena", 3L));
    }

    @Test
    void summaryPassesThroughHonestNullMttrAndUses24hWindow() {
        reader.summary = new IncidentSummary(4, Map.of("critical", 1L), 2, 99, 12, null);

        IncidentSummary out = service.summary();

        assertThat(out.mttrMinutes24h()).isNull();
        assertThat(out.firingTotal()).isEqualTo(4);
        assertThat(reader.lastSince).isEqualTo(SINCE);
    }

    // ------------------------------------------------------------------ 总览装配

    @Test
    void overviewAssemblesRunsCasesNotificationsDutyAndZeroFilledTrend() {
        reader.overview = new AlertOverview(3, new RunsStats(2, 1, 0, 5, 120L),
                List.of(new TrendBucket(NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                        .minus(java.time.Duration.ofHours(2)), 4, 1),
                        new TrendBucket(NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                                7, 0)));
        dutyStore.unread = 6;
        caseRepository.rows = List.of(
                operatorCase("op", null),
                operatorCase(null, NOW.minus(java.time.Duration.ofHours(1))), // unassigned+overdue
                operatorCase(null, null, CaseStatus.RESOLVED));               // 排除出 open 面

        IncidentQueryService.OverviewResponse out = service.overview("op");

        assertThat(out.firingIncidents()).isEqualTo(3);
        assertThat(out.runs().active()).isEqualTo(2);
        assertThat(out.runs().awaitingReview()).isEqualTo(1);
        assertThat(out.runs().oldestReadyWaitSeconds()).isEqualTo(120L);
        // cases 口径 = OperatorQueryService.summary tabs（open=all、排除 RESOLVED）
        assertThat(out.cases().open()).isEqualTo(2);
        assertThat(out.cases().unassigned()).isEqualTo(1);
        assertThat(out.cases().overdue()).isEqualTo(1);
        assertThat(out.notifications().unread()).isEqualTo(6);
        assertThat(out.duty().oncall()).isEqualTo("alice");
        assertThat(out.duty().snapshotValidUntil())
                .isEqualTo(NOW.plus(java.time.Duration.ofHours(1)));

        List<TrendBucket> trend = out.alertTrend24h();
        assertThat(trend).hasSize(24);
        Instant lastBucket = NOW.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        assertThat(trend.get(23).bucketStart()).isEqualTo(lastBucket);
        assertThat(trend.get(23).received()).isEqualTo(7);
        assertThat(trend.get(0).bucketStart())
                .isEqualTo(lastBucket.minus(java.time.Duration.ofHours(23)));
        assertThat(trend.get(21).received()).isEqualTo(4);
        assertThat(trend.get(21).resolved()).isEqualTo(1);
        assertThat(trend.get(22).received()).isZero(); // 缺桶补 0
    }

    // ------------------------------------------------------------------ fakes

    private static IncidentRow row(UUID id, Instant lastEventAt) {
        return new IncidentRow(id, "key-" + id, "HighCpu", "order-arena", "critical",
                "FIRING", NOW.minusSeconds(3600), lastEventAt, null, 10, 2, 1,
                UUID.randomUUID(), "RUNNING", null);
    }

    private static OperatorCase operatorCase(String owner, Instant resolveDue) {
        return operatorCase(owner, resolveDue, CaseStatus.OPEN);
    }

    private static OperatorCase operatorCase(String owner, Instant resolveDue, CaseStatus status) {
        return new OperatorCase(UUID.randomUUID(), "t", "fp-" + UUID.randomUUID(),
                "subject", "P1", "ALERT_FIRING", status, owner, null, null, null, null, 0,
                List.of("evidence-1"), List.of(), List.of(), null, NOW, null, resolveDue,
                1, NOW, NOW);
    }

    private static final class FakeReader implements IncidentQueryReader {
        IncidentPage page = new IncidentPage(List.of(), 0, false);
        Facets facets = new Facets(Map.of(), Map.of(), Map.of());
        IncidentSummary summary = new IncidentSummary(0, Map.of(), 0, 0, 0, null);
        AlertOverview overview = new AlertOverview(0, new RunsStats(0, 0, 0, 0, null),
                List.of());
        String lastStatus;
        String lastSeverity;
        String lastService;
        String lastQ;
        KeysetCursor lastCursor;
        int lastLimit;
        Instant lastSince;

        @Override
        public IncidentPage listIncidents(String status, String severity, String service,
                                          String q, KeysetCursor cursor, int limit) {
            this.lastStatus = status;
            this.lastSeverity = severity;
            this.lastService = service;
            this.lastQ = q;
            this.lastCursor = cursor;
            this.lastLimit = limit;
            return page;
        }

        @Override
        public Optional<IncidentDetail> detail(UUID incidentId) {
            return Optional.empty();
        }

        @Override
        public Facets facets(String status, String service, String q) {
            return facets;
        }

        @Override
        public IncidentSummary summary(Instant since) {
            this.lastSince = since;
            return summary;
        }

        @Override
        public AlertOverview overview(Instant now) {
            return overview;
        }
    }

    /** 单成员层 → 当班恒 alice；快照 validUntil=NOW+1h */
    private static final class FakeDutyStore implements DutyStore {
        long unread;

        @Override
        public DutyScheduleSnapshot loadSnapshot() {
            return new DutyScheduleSnapshot(UUID.randomUUID(), "primary", 1L, NOW,
                    NOW.plus(java.time.Duration.ofHours(1)), ZoneId.of("UTC"),
                    RotationMath.Rotation.DAILY, LocalDate.of(2026, 9, 1),
                    LocalTime.of(9, 0),
                    List.of(new DutyScheduleSnapshot.Layer(0, List.of("alice"))),
                    List.of(), List.of());
        }

        @Override
        public long unreadCount() {
            return unread;
        }

        @Override
        public DispatchOutcome insertNotificationWithFirstDelivery(NewNotification n,
                                                                   DutyScheduleSnapshot.Channel c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DispatchOutcome insertExternalNotification(NewNotification notification) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean insertDeliveryIfAbsent(UUID id, DutyScheduleSnapshot.Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DeadDelivery> findDeadDeliveriesUpdatedSince(Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markRead(UUID id, Instant readAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DutyNotificationView> listNotifications(String cursor, int limit,
                                                            boolean unreadOnly) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<NotificationFeedView> listFeed(String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<UUID, List<DeliveryView>> listDeliveries(List<UUID> notificationIds) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeCaseRepository implements OperatorCaseRepository {
        List<OperatorCase> rows = new ArrayList<>();

        @Override
        public void insert(OperatorCase operatorCase) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<OperatorCase> lockByTenantAndFingerprint(String tenant, String fingerprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<OperatorCase> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<OperatorCase> findAll() {
            return rows;
        }

        @Override
        public boolean update(OperatorCase operatorCase, long expectedRevision) {
            throw new UnsupportedOperationException();
        }
    }
}
