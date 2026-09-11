package com.objwww.pr.control.drill.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillEvent;
import com.objwww.pr.control.drill.domain.model.DrillLaunchPlan;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.drill.domain.repository.DrillEventRepository;
import com.objwww.pr.control.drill.domain.repository.DrillJobRepository;
import com.objwww.pr.control.drill.domain.statemachine.DrillLifecycle;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * DR-02 演练命令+查询服务（/api/drills 面应用层；control_app 身份——V86 只授
 * drill_job select,insert + 停止两列 update，状态机推进零开口：HTTP 线程不驱动
 * 相位，作业先持久化，执行归 drill worker）。
 *
 * <p>冻结语义：
 * <ul>
 *   <li><b>服务端预检重执行</b>（§7.2）：preview 与 create 走同一 {@link DrillPrecheck}，
 *       旧预览不能保证现在仍可启动——create 受理前必重跑，FAIL 即 409 带检查清单；</li>
 *   <li><b>幂等</b>（DU02）：同 idempotencyKey 撞 uq → 比 payload_hash——同计划
 *       返回原作业（REPLAYED），异计划 CONFLICT_KEY（409）；撞活动占位 uq 而键不同
 *       = 环境互斥 CONFLICT_ENV（409 带占用作业，DU05/DU15）；</li>
 *   <li><b>停止</b>（DU12/DU14）：受理只置 stop 两列 + STOP_REQUESTED 审计事件——
 *       受理 ≠ 恢复完成；注入前 = CANCELLING，注入可能发生起 = RECOVERING（核验完成
 *       才 CLOSED，归 worker）；同 stop 键重放 REPLAYED，异键重复停止幂等无新副作用，
 *       终态/恢复异常占位 409；</li>
 *   <li><b>actor</b> 唯一来源 = 认证主体（AuthenticatedActor），请求体不自报。</li>
 * </ul>
 */
public class DrillJobService {

    // ------------------------------------------------------------------ DTO

    public record TemplateCard(String scenarioId, String name, String scenarioType,
                               String faultSource, String driver, String chaosFamily,
                               String target, List<String> symptomCodes,
                               String symptomDisplay, String impact,
                               Map<String, Integer> timing, Map<String, Object> params,
                               Map<String, Object> execution) {
    }

    public record TemplateListResponse(int registryVersion, String catalogDigest,
                                       List<TemplateCard> templates) {
    }

    public record ComputedView(int durationSeconds, long ttlSeconds,
                               long totalEstimateSeconds, int preheatSeconds,
                               int recoveryWindowSeconds) {
    }

    public record PreviewResponse(TemplateCard template, ComputedView computed,
                                  List<DrillPrecheck.Check> checks, boolean canLaunch,
                                  Instant checkedAt) {
    }

    public enum CreateStatus {ACCEPTED, REPLAYED, CONFLICT_KEY, CONFLICT_ENV, PRECHECK_FAILED}

    public record CreateResult(CreateStatus status, UUID drillId,
                               DrillPrecheck.Result precheck) {
    }

    public enum StopStatus {
        ACCEPTED_RECOVERING, ACCEPTED_CANCELLING, ALREADY_REQUESTED, REPLAYED,
        CONFLICT_TERMINAL, CONFLICT_RECOVERY_FAILED, CONFLICT_KEY
    }

    public record StopResult(StopStatus status, DrillJob.State state) {
    }

    public record ListItem(String drillId, String scenarioId, String scenarioName,
                           String targetEnv, String operator, String state, String outcome,
                           Instant createdAt, Instant closedAt) {
    }

    public record Summary(long active, long recoveryFailed) {
    }

    public record ListResponse(List<ListItem> items, Summary summary, String nextCursor,
                               Instant asOf) {
    }

    public record Related(UUID incidentId, UUID runId) {
    }

    public record DetailResponse(String drillId, String scenarioId, String scenarioName,
                                 String targetEnv, String operator, String state,
                                 String outcome, String terminalReason,
                                 String templateDigest, Map<String, Object> params,
                                 List<DrillTimeline.Stage> timeline, Related related,
                                 Instant createdAt, Instant updatedAt, Instant closedAt,
                                 Instant stopRequestedAt) {
    }

    // ------------------------------------------------------------------ 装配

    private final DrillJobRepository jobs;
    private final DrillEventRepository events;
    private final DrillTemplateCatalog catalog;
    private final ObjectMapper mapper;
    private final List<String> allowedEnvs;

    public DrillJobService(DrillJobRepository jobs, DrillEventRepository events,
                           DrillTemplateCatalog catalog, ObjectMapper mapper,
                           List<String> allowedEnvs) {
        this.jobs = Objects.requireNonNull(jobs);
        this.events = Objects.requireNonNull(events);
        this.catalog = Objects.requireNonNull(catalog);
        this.mapper = Objects.requireNonNull(mapper);
        this.allowedEnvs = List.copyOf(allowedEnvs);
        if (this.allowedEnvs.isEmpty()) {
            throw new IllegalArgumentException("靶场白名单不得为空");
        }
    }

    // ------------------------------------------------------------------ 模板目录

    public TemplateListResponse templates() {
        return new TemplateListResponse(catalog.registryVersion(),
                catalog.contentDigest().value(),
                catalog.templates().stream().map(this::card).toList());
    }

    private TemplateCard card(DrillTemplate t) {
        Map<String, Integer> timing = new LinkedHashMap<>();
        timing.put("preheatSeconds", t.timing().preheatSeconds());
        timing.put("holdSeconds", t.timing().holdSeconds());
        timing.put("maxFiringWaitSeconds", t.timing().maxFiringWaitSeconds());
        timing.put("maxResolvedWaitSeconds", t.timing().maxResolvedWaitSeconds());
        timing.put("cleanupTimeoutSeconds", t.timing().cleanupTimeoutSeconds());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("durationDefaultSeconds", t.params().durationDefaultSeconds());
        params.put("durationMinSeconds", t.params().durationMinSeconds());
        params.put("durationMaxSeconds", t.params().durationMaxSeconds());
        params.put("trafficScales", t.params().trafficScales());
        params.put("linkedEvalVersionAllowed", t.params().linkedEvalVersionAllowed());
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("ready", t.execution().ready());
        execution.put("reason", t.execution().reason());
        return new TemplateCard(t.scenarioId(), t.name(), t.scenarioType(), t.faultSource(),
                t.driver(), t.chaosFamily(), t.target(), t.symptomCodes(),
                t.symptomDisplay(), t.impact(), timing, params, execution);
    }

    // ------------------------------------------------------------------ 预检预览

    /** 服务端预检（§7.2：目标资源/预计等待/停止条件/恢复方式/资源占用 的真实计算面） */
    public PreviewResponse preview(DrillLaunchPlan plan) {
        DrillTemplate template = resolve(plan);
        DrillPrecheck.Result precheck = DrillPrecheck.run(template, plan.targetEnv(),
                allowedEnvs, jobs.findActiveOccupant(plan.targetEnv(), null).orElse(null));
        return new PreviewResponse(card(template), computed(template, plan),
                precheck.checks(), precheck.canLaunch(), Instant.now());
    }

    // ------------------------------------------------------------------ 发起

    /** 幂等发起：先对幂等锚（重放不要求环境空闲——占用者就是原作业），再预检重执行 */
    public CreateResult create(DrillLaunchPlan plan, String idempotencyKey, String actor) {
        validateKey(idempotencyKey);
        DrillTemplate template = resolve(plan);
        String payloadHash = plan.payloadHash().value();
        Optional<DrillJob> prior = jobs.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            DrillJob winner = prior.get();
            return new CreateResult(winner.payloadHash().equals(payloadHash)
                    ? CreateStatus.REPLAYED : CreateStatus.CONFLICT_KEY,
                    winner.id(), null);
        }
        DrillPrecheck.Result precheck = DrillPrecheck.run(template, plan.targetEnv(),
                allowedEnvs, jobs.findActiveOccupant(plan.targetEnv(), null).orElse(null));
        if (!precheck.canLaunch()) {
            return new CreateResult(CreateStatus.PRECHECK_FAILED, null, precheck);
        }
        UUID drillId = UUID.randomUUID();
        DrillJob job = DrillJob.queued(drillId, template.scenarioId(), template.name(),
                catalog.contentDigest().value(), plan.targetEnv(), actor,
                paramsJson(template, plan), payloadHash, idempotencyKey,
                Instant.now());
        try {
            jobs.insert(job);
            return new CreateResult(CreateStatus.ACCEPTED, drillId, precheck);
        } catch (DuplicateKeyException e) {
            // 并发撞键（读检查与插入之间）：读胜者行比 hash（DU02 判定锚）；
            // 键不同 = 环境互斥占位（DU05/DU15）
            Optional<DrillJob> existing = jobs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                DrillJob winner = existing.get();
                return new CreateResult(
                        winner.payloadHash().equals(payloadHash)
                                ? CreateStatus.REPLAYED : CreateStatus.CONFLICT_KEY,
                        winner.id(), precheck);
            }
            UUID occupant = jobs.findActiveOccupant(plan.targetEnv(), null)
                    .map(DrillJob::id).orElse(null);
            return new CreateResult(CreateStatus.CONFLICT_ENV, occupant, precheck);
        }
    }

    // ------------------------------------------------------------------ 停止

    /**
     * 停止受理：作业不存在 → empty（404 面）；同 stop 键 → REPLAYED/CONFLICT_KEY；
     * 终态/RECOVERY_FAILED → 409；已受理过（异键）→ ALREADY_REQUESTED 幂等无新副作用；
     * 合法 → 置 stop 两列 + STOP_REQUESTED 审计（受理 ≠ 恢复完成，推进归 worker）。
     */
    public Optional<StopResult> stop(UUID drillId, String stopIdempotencyKey, String actor) {
        validateKey(stopIdempotencyKey);
        Optional<DrillJob> maybe = jobs.findById(drillId);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        DrillJob job = maybe.get();
        Optional<DrillJob> prior = jobs.findByStopKey(stopIdempotencyKey);
        if (prior.isPresent()) {
            return Optional.of(new StopResult(
                    prior.get().id().equals(drillId)
                            ? StopStatus.REPLAYED : StopStatus.CONFLICT_KEY,
                    job.state()));
        }
        if (job.state().isTerminal()) {
            return Optional.of(new StopResult(StopStatus.CONFLICT_TERMINAL, job.state()));
        }
        if (job.state() == DrillJob.State.RECOVERY_FAILED) {
            // 恢复异常占位：需要的是处理恢复而非停止（§7.4 保留占位与处理入口）
            return Optional.of(new StopResult(StopStatus.CONFLICT_RECOVERY_FAILED,
                    job.state()));
        }
        if (job.stopRequestedAt() != null) {
            // 异键重复停止：幂等无新副作用（DU14 重复停止幂等）
            return Optional.of(new StopResult(StopStatus.ALREADY_REQUESTED, job.state()));
        }
        Instant now = Instant.now();
        boolean accepted = jobs.requestStop(drillId, stopIdempotencyKey, now);
        if (!accepted) {
            // CAS 竞争（与 worker 终态化/并发停止撞车）：以最新状态如实重答
            DrillJob current = jobs.findById(drillId).orElse(job);
            if (current.state().isTerminal()) {
                return Optional.of(new StopResult(StopStatus.CONFLICT_TERMINAL,
                        current.state()));
            }
            return Optional.of(new StopResult(StopStatus.ALREADY_REQUESTED,
                    current.state()));
        }
        events.insert(DrillEvent.of(drillId, DrillEvent.EventType.STOP_REQUESTED, actor,
                stopEventJson(stopIdempotencyKey, job.state()), now));
        DrillJob.State path = DrillLifecycle.stopPath(job.state());
        return Optional.of(new StopResult(path == DrillJob.State.CANCELLED
                ? StopStatus.ACCEPTED_CANCELLING : StopStatus.ACCEPTED_RECOVERING,
                job.state()));
    }

    // ------------------------------------------------------------------ 查询投影

    public ListResponse list(String state, String cursor, int limit) {
        if (state != null) {
            try {
                DrillJob.State.valueOf(state);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("state 非法: " + state);
            }
        }
        List<DrillJob> rows = jobs.list(state, cursor, limit);
        List<ListItem> items = rows.stream()
                .map(j -> new ListItem(j.id().toString(), j.scenarioId(), j.scenarioName(),
                        j.targetEnv(), j.operator(), j.state().name(), j.outcome(),
                        j.createdAt(), j.closedAt()))
                .toList();
        String nextCursor = rows.size() == limit
                ? cursorOf(rows.getLast()) : null;
        return new ListResponse(items,
                new Summary(jobs.countActive(), jobs.countRecoveryFailed()),
                nextCursor, Instant.now());
    }

    public Optional<DetailResponse> detail(UUID drillId) {
        return jobs.findById(drillId).map(job -> {
            List<DrillEvent> drillEvents = events.listByDrill(drillId);
            return new DetailResponse(job.id().toString(), job.scenarioId(),
                    job.scenarioName(), job.targetEnv(), job.operator(), job.state().name(),
                    job.outcome(), job.terminalReason(), job.templateDigest(),
                    paramsMap(job.paramsJson()),
                    DrillTimeline.of(job, drillEvents),
                    new Related(job.relatedIncidentId(), job.relatedRunId()),
                    job.createdAt(), job.updatedAt(), job.closedAt(),
                    job.stopRequestedAt());
        });
    }

    /** 键集游标（created_at 微秒|id；与 Postgres 实现的比较序一致） */
    public static String cursorOf(DrillJob job) {
        long micros = job.createdAt().getEpochSecond() * 1_000_000
                + job.createdAt().getNano() / 1000;
        return micros + "|" + job.id();
    }

    // ------------------------------------------------------------------ 内部

    /** 场景解析 + 白名单校验（DU04：服务端强制，篡改即 400） */
    private DrillTemplate resolve(DrillLaunchPlan plan) {
        DrillTemplate template = catalog.byScenarioId(plan.scenarioId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "场景未注册于模板目录: " + plan.scenarioId()));
        if (!allowedEnvs.contains(plan.targetEnv())) {
            throw new IllegalArgumentException("靶场不在部署白名单内: " + plan.targetEnv());
        }
        if (plan.durationSeconds() != null
                && (plan.durationSeconds() < template.params().durationMinSeconds()
                || plan.durationSeconds() > template.params().durationMaxSeconds())) {
            throw new IllegalArgumentException("durationSeconds 越出场景白名单区间 ["
                    + template.params().durationMinSeconds() + ", "
                    + template.params().durationMaxSeconds() + "]");
        }
        if (plan.trafficScale() != null
                && !template.params().trafficScales().contains(plan.trafficScale())) {
            throw new IllegalArgumentException("trafficScale 不在场景白名单内: "
                    + plan.trafficScale());
        }
        if (plan.linkedEvalVersion() != null
                && !template.params().linkedEvalVersionAllowed()) {
            throw new IllegalArgumentException("本场景不开放关联评测版本");
        }
        return template;
    }

    /** 后端计算实际 TTL/预热/恢复窗口（§7.2：不允许前端各算一套；TTL 公式沿
     *  ArenaChaosScenarioDriver：preheat + hold + maxResolvedWait） */
    private ComputedView computed(DrillTemplate template, DrillLaunchPlan plan) {
        int duration = plan.durationSeconds() != null
                ? plan.durationSeconds() : template.params().durationDefaultSeconds();
        long ttl = (long) template.timing().preheatSeconds() + duration
                + template.timing().maxResolvedWaitSeconds();
        long total = (long) template.timing().preheatSeconds() + duration
                + template.timing().maxFiringWaitSeconds()
                + template.timing().maxResolvedWaitSeconds()
                + template.timing().cleanupTimeoutSeconds();
        return new ComputedView(duration, ttl, total, template.timing().preheatSeconds(),
                template.timing().maxResolvedWaitSeconds()
                        + template.timing().cleanupTimeoutSeconds());
    }

    /** 冻结参数快照（固定字段序；启动时冻结，运行中模板发布不改变本场，§7.4） */
    private String paramsJson(DrillTemplate template, DrillLaunchPlan plan) {
        ComputedView c = computed(template, plan);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scenarioId", template.scenarioId());
        map.put("targetEnv", plan.targetEnv());
        map.put("durationSeconds", c.durationSeconds());
        map.put("trafficScale", plan.trafficScale() != null
                ? plan.trafficScale() : template.params().trafficScales().getFirst());
        map.put("linkedEvalVersion", plan.linkedEvalVersion());
        map.put("ttlSeconds", c.ttlSeconds());
        map.put("totalEstimateSeconds", c.totalEstimateSeconds());
        map.put("registryVersion", catalog.registryVersion());
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("drill params 序列化失败", e);
        }
    }

    private String stopEventJson(String stopIdempotencyKey, DrillJob.State fromState) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "stopIdempotencyKey", stopIdempotencyKey,
                    "requestedAtState", fromState.name()));
        } catch (Exception e) {
            throw new IllegalStateException("stop 事件序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsMap(String paramsJson) {
        try {
            return mapper.readValue(paramsJson, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("drill params 反序列化失败", e);
        }
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey 必填且 ≤128 字符");
        }
    }
}
