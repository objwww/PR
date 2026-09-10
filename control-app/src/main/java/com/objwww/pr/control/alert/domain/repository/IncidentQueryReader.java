package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-1 告警查询投影只读端口（incident/alert_event/rca_run 三表投影；零写面）。
 *
 * <p>incident 无 severity/alertname/service 独立列（INV-AM1-4：升级不换单）——三值由
 * 实现方从"每 incident 最新一条 alert_event"的 labels jsonb 提取（service 兼容
 * {@code service}/{@code service_name} 两键），labels 无对应键时如实 null。
 *
 * <p>键集分页：排序 (last_event_at DESC, id DESC)，cursor = 上一页末行的
 * (lastEventAt, incidentId)——明文拼接/解析归应用服务，端口只收结构化游标。
 */
public interface IncidentQueryReader {

    /** 键集游标（(last_event_at, id) 严格小于继续取页） */
    record KeysetCursor(Instant at, UUID id) {
    }

    /** 列表/详情共用的 incident 投影行（labels 三值 + 当前 run 态已展开） */
    record IncidentRow(UUID incidentId, String incidentKey, String alertname, String service,
                       String severity, String status, Instant episodeStartedAt,
                       Instant lastEventAt, Instant resolvedAt, long receivedCount,
                       long distinctEventCount, long notificationCount,
                       UUID currentRcaRunId, String runState, String waitingReason) {
    }

    /** 一页 + 过滤后总数；hasMore = 取到 limit+1 行（调用方据此发 nextCursor） */
    record IncidentPage(List<IncidentRow> items, long total, boolean hasMore) {
    }

    /** 时间线行（alert_event 按 starts_at 升序；severity 由该行 labels 提取） */
    record TimelineEvent(UUID eventId, String status, Instant startsAt, Instant endsAt,
                         String severity, Map<String, Object> labels,
                         Map<String, Object> annotations) {
    }

    /** 当前 run 徽标（incident.current_rca_run_id 对应行；无则 detail.run=null） */
    record RunBadge(UUID runId, String state, Instant startedAt, Instant finishedAt) {
    }

    /** 详情投影：行 + 最新事件 labels/annotations 全文 + 全量时间线 + 当前 run */
    record IncidentDetail(IncidentRow row, Map<String, Object> labels,
                          Map<String, Object> annotations, List<TimelineEvent> timeline,
                          RunBadge run) {
    }

    /** facet 计数（键=原始 label 值；labels 无键的行不进桶） */
    record Facets(Map<String, Long> status, Map<String, Long> severity,
                  Map<String, Long> service) {
    }

    /**
     * 统计条（mttrMinutes24h 诚实 null：近 24h 无 resolved incident 时无均值可算，
     * 不回填 0——RunQueryService 同律）。
     */
    record IncidentSummary(long firingTotal, Map<String, Long> bySeverity, long unassigned,
                           long stormReceived24h, long stormEvents24h,
                           Double mttrMinutes24h) {
    }

    /**
     * runs 队列计数（口径同 RunQueryService C-18④：awaitingReview=SUCCEEDED+PARTIAL
     * 报告待人工复核；stuck=活跃 run 且存在 BLOCKED/RETRY_WAIT/超期 LEASED 任务；
     * oldestReadyWaitSeconds 无 READY 任务时如实 null）。
     */
    record RunsStats(long active, long awaitingReview, long stuck, long failed24h,
                     Long oldestReadyWaitSeconds) {
    }

    /** 小时趋势桶（starts_at 按 UTC 整点截断；received/resolved=该行数） */
    record TrendBucket(Instant bucketStart, long received, long resolved) {
    }

    /** 总览的告警侧聚合（cases/notifications/duty 由服务层经各自端口装配） */
    record AlertOverview(long firingIncidents, RunsStats runs, List<TrendBucket> trend24h) {
    }

    /**
     * 列表页（cursor=null 首页；各过滤参数 null=不过滤；q 对 incident_key 与最新
     * 事件 alertname 做 ILIKE）。实现方内部取 limit+1 判 hasMore。
     */
    IncidentPage listIncidents(String status, String severity, String service, String q,
                               KeysetCursor cursor, int limit);

    /** 详情；未知 id → empty（controller 404 面） */
    Optional<IncidentDetail> detail(UUID incidentId);

    /** facet 计数（接受 status/service/q 过滤；severity 维不过滤自身参数） */
    Facets facets(String status, String service, String q);

    /** 统计条（since = now-24h：storm/mttr 窗；firing/unassigned 为当前态） */
    IncidentSummary summary(Instant since);

    /** 总览告警侧聚合（now 参与 stuck 超期判定与 24h 窗） */
    AlertOverview overview(Instant now);
}
