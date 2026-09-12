package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.AlertOverview;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.Facets;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentDetail;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentPage;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentRow;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.IncidentSummary;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.KeysetCursor;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.RunBadge;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.RunsStats;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.TimelineEvent;
import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader.TrendBucket;
import com.objwww.pr.control.ops.application.OperatorQueryService;
import com.objwww.pr.control.ops.duty.domain.DutyResolver;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * UI-1 告警只读查询投影服务（/api/v1/** 面；SQL 归 {@link IncidentQueryReader}，
 * 本类只承担游标编解码、参数校验、facet 种子桶、24h 趋势补零与总览跨域装配——
 * 全部纯函数段，假端口可测，沿 RunQueryServiceTest 单测模式）。
 *
 * <p>游标明文拼接（沿 DutyQueryController "值/id" 惯例）：{@code <lastEventAt-ISO>/<incidentId>}。
 * 诚实纪律（RunQueryService 同律）：算不出的字段（mttr/oldestReadyWait）如实 null，不回填 0。
 */
public class IncidentQueryService {

    private static final Duration WINDOW_24H = Duration.ofHours(24);

    private final IncidentQueryReader reader;
    private final DutyStore dutyStore;
    private final OperatorQueryService operatorQuery;
    private final Supplier<Instant> now;

    public IncidentQueryService(IncidentQueryReader reader, DutyStore dutyStore,
                                OperatorQueryService operatorQuery, Supplier<Instant> now) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.dutyStore = Objects.requireNonNull(dutyStore, "dutyStore");
        this.operatorQuery = Objects.requireNonNull(operatorQuery, "operatorQuery");
        this.now = Objects.requireNonNull(now, "now");
    }

    // ------------------------------------------------------------------ DTO（record，字段名即 JSON 契约）

    public record IncidentListResponse(List<IncidentRow> items, String nextCursor, long total) {
    }

    public record IncidentDetailResponse(UUID incidentId, String incidentKey, String alertname,
                                         String service, String severity, String status,
                                         Instant episodeStartedAt, Instant lastEventAt,
                                         Instant resolvedAt, long receivedCount,
                                         long distinctEventCount, long notificationCount,
                                         UUID currentRcaRunId, String runState,
                                         String waitingReason,
                                         String category, String categorySource,
                                         IncidentQueryReader.CategoryDetail categoryDetail,
                                         Map<String, Object> labels,
                                         Map<String, Object> annotations,
                                         List<TimelineEvent> timeline, RunBadge run) {
    }

    public record FacetsResponse(Map<String, Long> status, Map<String, Long> severity,
                                 Map<String, Long> service, Map<String, Long> category) {
    }

    public record CasesStats(long open, long unassigned, long overdue) {
    }

    /** unread=duty 通知未读；failed24h=§三.1 通知失败 24h（notify_outbox DEAD 口径） */
    public record NotificationsStats(long unread, long failed24h) {
    }

    public record DutyBadge(String oncall, Instant snapshotValidUntil) {
    }

    /** topRisk=§三.1 按风险待办（IncidentRow 形状，上限 5，severity 风险序） */
    public record OverviewResponse(long firingIncidents, RunsStats runs, CasesStats cases,
                                   NotificationsStats notifications, DutyBadge duty,
                                   List<TrendBucket> alertTrend24h, List<IncidentRow> topRisk) {
    }

    // ------------------------------------------------------------------ 列表 / 详情 / facet / 统计条

    /** status/category 非法、时间窗/hasOwner 无法解析或 cursor 无法解析 →
     *  IllegalArgumentException（controller 400 面）；
     *  UX-03（方案 §三.2 高级筛选）：from/to 为 last_event_at ISO-8601 闭区间端点，
     *  hasOwner 取 "true"/"false" */
    public IncidentListResponse list(String status, String severity, String service,
                                     String q, String category, String from, String to,
                                     String hasOwner, String cursor, int limit) {
        validateStatus(status);
        validateCategory(category);
        Instant fromAt = parseWindow(from, "from");
        Instant toAt = parseWindow(to, "to");
        if (fromAt != null && toAt != null && fromAt.isAfter(toAt)) {
            throw new IllegalArgumentException("from 不得晚于 to");
        }
        IncidentPage page = reader.listIncidents(status, severity, service, q, category,
                fromAt, toAt, parseHasOwner(hasOwner), parseCursor(cursor), limit);
        String nextCursor = null;
        if (page.hasMore() && !page.items().isEmpty()) {
            IncidentRow last = page.items().get(page.items().size() - 1);
            nextCursor = last.lastEventAt() + "/" + last.incidentId();
        }
        return new IncidentListResponse(page.items(), nextCursor, page.total());
    }

    public Optional<IncidentDetailResponse> detail(UUID incidentId) {
        return reader.detail(incidentId).map(IncidentQueryService::flatten);
    }

    /** status 维度种子 FIRING/RESOLVED=0（契约示例双键恒在）；severity/service/category 只出现实测键 */
    public FacetsResponse facets(String status, String service, String q) {
        validateStatus(status);
        Facets facets = reader.facets(status, service, q);
        Map<String, Long> statusFacet = new LinkedHashMap<>();
        statusFacet.put("FIRING", 0L);
        statusFacet.put("RESOLVED", 0L);
        statusFacet.putAll(facets.status());
        return new FacetsResponse(statusFacet, facets.severity(), facets.service(),
                facets.category());
    }

    public IncidentSummary summary() {
        return reader.summary(now.get().minus(WINDOW_24H));
    }

    // ------------------------------------------------------------------ 总览（跨域装配）

    /**
     * cases 口径复用 {@link OperatorQueryService#summary}（open=tabs.all，
     * unassigned/overdue 同名 tab）；duty 复用排班快照 + DutyResolver 当班解析。
     */
    public OverviewResponse overview(String actor) {
        Instant at = now.get();
        AlertOverview alert = reader.overview(at);

        @SuppressWarnings("unchecked")
        Map<String, Object> tabs =
                (Map<String, Object>) operatorQuery.summary(actor).get("tabs");
        CasesStats cases = new CasesStats(longOf(tabs.get("all")),
                longOf(tabs.get("unassigned")), longOf(tabs.get("overdue")));

        DutyScheduleSnapshot snapshot = dutyStore.loadSnapshot();
        DutyResolver.Resolution resolved = DutyResolver.resolve(at, snapshot);
        DutyBadge duty = new DutyBadge(resolved.onCall(), snapshot.validUntil());

        return new OverviewResponse(alert.firingIncidents(), alert.runs(), cases,
                new NotificationsStats(dutyStore.unreadCount(), alert.notifyFailed24h()), duty,
                zeroFillTrend(alert.trend24h(), at), alert.topRisk());
    }

    // ------------------------------------------------------------------ 内部

    /** 24 个整点桶（UTC，末桶=now 所在小时），缺失小时补 0 */
    static List<TrendBucket> zeroFillTrend(List<TrendBucket> sparse, Instant at) {
        Instant last = at.truncatedTo(ChronoUnit.HOURS);
        Map<Instant, TrendBucket> byStart = new LinkedHashMap<>();
        for (TrendBucket bucket : sparse) {
            byStart.put(bucket.bucketStart().truncatedTo(ChronoUnit.HOURS), bucket);
        }
        List<TrendBucket> out = new ArrayList<>(24);
        for (int i = 23; i >= 0; i--) {
            Instant start = last.minus(Duration.ofHours(i));
            TrendBucket hit = byStart.get(start);
            out.add(hit != null ? new TrendBucket(start, hit.received(), hit.resolved())
                    : new TrendBucket(start, 0L, 0L));
        }
        return out;
    }

    private static IncidentDetailResponse flatten(IncidentDetail detail) {
        IncidentRow row = detail.row();
        return new IncidentDetailResponse(row.incidentId(), row.incidentKey(), row.alertname(),
                row.service(), row.severity(), row.status(), row.episodeStartedAt(),
                row.lastEventAt(), row.resolvedAt(), row.receivedCount(),
                row.distinctEventCount(), row.notificationCount(), row.currentRcaRunId(),
                row.runState(), row.waitingReason(), row.category(), row.categorySource(),
                detail.categoryDetail(), detail.labels(), detail.annotations(),
                detail.timeline(), detail.run());
    }

    private static void validateStatus(String status) {
        if (status != null && !status.isBlank()
                && !"FIRING".equals(status) && !"RESOLVED".equals(status)) {
            throw new IllegalArgumentException("status 必为 FIRING/RESOLVED: " + status);
        }
    }

    /** UX-01：category 过滤词表校验（与 V82 CHECK / IncidentCategory 枚举同词表） */
    private static void validateCategory(String category) {
        if (category == null || category.isBlank()) {
            return;
        }
        com.objwww.pr.control.alert.domain.classification.IncidentCategory.parse(category)
                .orElseThrow(() -> new IllegalArgumentException(
                        "category 非法（词表见 IncidentCategory）: " + category));
    }

    /** UX-03：时间窗端点解析（ISO-8601 瞬秒；非法 → 400 面，cursor 同律） */
    private static Instant parseWindow(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    name + " 非法（期形 ISO-8601 瞬秒，如 2026-09-12T00:00:00Z）: " + value);
        }
    }

    /** UX-03：hasOwner 词表校验（"true"/"false"，大小写不敏感；其余 → 400 面） */
    private static Boolean parseHasOwner(String hasOwner) {
        if (hasOwner == null || hasOwner.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(hasOwner)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(hasOwner)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("hasOwner 必为 true/false: " + hasOwner);
    }

    private static KeysetCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = cursor.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("cursor 非法（期形 <lastEventAt>/<incidentId>）");
        }
        try {
            return new KeysetCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor 非法（期形 <lastEventAt>/<incidentId>）");
        }
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
