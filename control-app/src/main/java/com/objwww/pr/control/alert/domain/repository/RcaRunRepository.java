package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunPurpose;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * rca_run 端口。
 *
 * <p>SQL 契约：insert 撞部分唯一索引 uq_rca_run_active_incident（incident_id, engine
 * where state in ('QUEUED','RUNNING')，V25 升 (incident_id, engine) 粒度）抛
 * DuplicateKeyException(23505)——INV-AM1-2 同一 incident 同引擎最多一个活跃 run 的
 * DB 强制（CT-A03 并发双铸实证）。
 */
public interface RcaRunRepository {

    void insert(RcaRun run);

    /**
     * 带路由四列铸造（M5-10；engine/config_digest/stickiness_key/canary_bucket 落行，
     * Run 启动固定不再变）。默认实现 = 无路由语义环境（测试 fake/降级仓储）回退普通
     * insert，列走 DB 默认（HOLMES/null/null/null）——行为与 insert 等价。
     */
    default void insertRouted(RcaRun run, RcaRunRouting routing) {
        insert(run);
    }

    /**
     * EX-A0 铸点身份冻结：路由四列 + 调查输入三列（investigation_input_digest/
     * window_start/window_end，V36）随行一次落库——Run 创建时冻结，执行期只读。
     * 默认实现 = 无身份语义环境（测试 fake）回退 {@link #insertRouted(RcaRun, RcaRunRouting)}。
     */
    default void insertRouted(RcaRun run, RcaRunRouting routing,
            com.objwww.pr.control.alert.domain.identity.InvestigationInputs inputs) {
        insertRouted(run, routing);
    }

    /**
     * JE-01：Jev 增强开关随铸造冻结（V159 jev_enabled 列）——三处铸造点读运行时
     * 开关随行落列，执行期只读；切换开关不改变在跑调查。默认实现 = 无该列语义的
     * 环境（测试 fake/降级仓储）忽略旗标，行为与三参版等价（false = 现有链路）。
     */
    default void insertRouted(RcaRun run, RcaRunRouting routing,
            com.objwww.pr.control.alert.domain.identity.InvestigationInputs inputs,
            boolean jevEnabled) {
        insertRouted(run, routing, inputs);
    }

    /**
     * JE-01：run 铸造时冻结的 Jev 增强开关单列读（执行面选材/复核触发判据；
     * 与路由四列同类的"行上元数据、不进聚合 record"读法）。默认 false = 现有链路。
     */
    default boolean jevEnabledById(UUID id) {
        return false;
    }

    /** 行锁（finishTask 收尾算法 "lock task → lock incident" 链路） */
    Optional<RcaRun> findByIdForUpdate(UUID id);

    /** 无锁读（评测评分轮询面，M3-16；只读身份用） */
    Optional<RcaRun> findById(UUID id);

    boolean update(RcaRun run);

    /**
     * EX-A2（F12/P1-04）：修订条件写（CAS）——取消/开跑类提交的线性化点。
     * expectedRevision 锚既有 {@code rca_run.last_event_seq}（M5-14 命令版本机制，
     * 不新造 revision 列）；WHERE 同时守活跃态（QUEUED/RUNNING/REPORTING）——
     * finishTask 类非事件型状态推进（不经事件账本推进修订号的写）也纳入栅栏。
     * SET 同 {@link #update}，且修订号 +1。影响行数 0 = 并发状态事实已先推进
     * （finishTask 收尾/取消已落地/命令已推进修订）= 本次提交失去资格，调用方
     * 按败者语义结算、零副作用。默认实现不可用（条件写必须真实现）。
     */
    default boolean updateIfRevision(RcaRun run, long expectedRevision) {
        throw new UnsupportedOperationException(
                "updateIfRevision 需原子条件写实现: " + getClass().getName());
    }

    /** 当前活跃 run（QUEUED/RUNNING）；无则 empty */
    Optional<RcaRun> findActiveByIncidentId(UUID incidentId);

    /**
     * 只读全量（M5-13 runs 队列投影输入；created_at 降序、id 升序稳定排序）。
     * 操作面规模（每 incident 一行）下无分页——游标分页列开放项 O-5。
     */
    List<RcaRun> findAll();

    /**
     * V25 路由四列只读投影（M5-13 detail 的 engine/config 面）。读视图独立于
     * 写面 {@link RcaRunRouting}：后者是铸造校验面（decision 必带），存量行
     * （plain insert → 引擎列默认 HOLMES、digest/key/bucket 可空）没有决策语义。
     */
    Optional<RoutingView> findRoutingById(UUID id);

    /**
     * rca_run 路由四列读视图（configDigest 十六进制或 null）。EX-A0 追加调查输入
     * 三列（V36）：investigationInputDigest/windowStart/windowEnd——存量行可空，
     * 执行面按 §3.2 兼容语义回退。
     */
    record RoutingView(RcaEngine engine, String configDigest, String stickinessKey,
                       Integer bucket, String investigationInputDigest,
                       java.time.Instant windowStart, java.time.Instant windowEnd) {
    }

    /**
     * incident 是否存在 NATIVE 路由 run（含终态——历史 canary 实跑即占住对照位）。
     * C-61 Shadow/Canary 互斥的判定源：NATIVE run 在场即停发 Native 影子；
     * 全 HOLMES incident 影子照发（供 V31 引擎对照）。
     */
    boolean existsNativeRunByIncidentId(UUID incidentId);

    /**
     * run 修订锚（M5-14 命令面）：= rca_run.last_event_seq——M4-10 计数器在
     * 状态事实推进时同行 +1，无洞单调，是 run 的天然修订号。run 不存在 → empty。
     */
    OptionalLong currentRevision(UUID id);

    // ------------------------------------------------------------------ SR 对账面（V108）/ WC-4 公平分页（V109）

    /**
     * SR §4.1 对账候选批扫描（WC-4 §6.1 改 keyset 分页）：活跃集（QUEUED/RUNNING/
     * REPORTING，谓词与 isActive()/V12 uq 索引同集）按 (created_at,id) 稳定排序、
     * 游标后取批上限——活跃 Run 超过批上限时不再"最老 N 条独占、新记录饿死"。
     * 游标 = 上一批末条的 (createdAt,id)；null 游标 = 从头。deadline/reportingStarted/
     * recoveryAttempts 不进 {@link RcaRun} 域记录（写面专用列），由本投影携带。
     *
     * <p>默认实现 = findAll 派生（薄 fake 语义等价：不跟踪对账专用列——全空/0）；
     * 生产实现（Postgres）必须以 V109 部分索引真查询覆盖。
     */
    default List<ReconcileCandidate> findActiveForReconcileAfter(Instant afterCreatedAt,
            UUID afterId, int limit) {
        return findAll().stream()
                .filter(r -> r.state().isActive())
                .filter(r -> afterCreatedAt == null
                        || r.createdAt().isAfter(afterCreatedAt)
                        || (r.createdAt().equals(afterCreatedAt)
                            && afterId != null && r.id().compareTo(afterId) > 0))
                .sorted(java.util.Comparator.comparing(RcaRun::createdAt)
                        .thenComparing(RcaRun::id))
                .limit(limit)
                .map(r -> new ReconcileCandidate(r.id(), r.incidentId(), r.state(),
                        r.generation(), r.purpose(), r.createdAt(), r.updatedAt(),
                        null, null, 0))
                .toList();
    }

    /**
     * 兼容面（SR §4.1 原批扫描）：= 从头取一批。新代码请用
     * {@link #findActiveForReconcileAfter(Instant, UUID, int)}。
     */
    default List<ReconcileCandidate> findActiveForReconcile(int limit) {
        return findActiveForReconcileAfter(null, null, limit);
    }

    /**
     * WC-4 §6.3：现行对账硬期限单列读（调用方已持 run 行锁时的锁内复验面——
     * 候选快照的 deadline 可能已被并发修宽/清除）。null = legacy 无可信期限。
     * 默认 Optional.empty()（无对账语义环境）。
     */
    default Optional<Instant> reconcileDeadlineById(UUID id) {
        return Optional.empty();
    }

    /**
     * WC-5：最老活跃 Run 的 createdAt（keyset 全覆盖下 = 最长未扫描时长的上界
     * 代理；无活跃 Run = empty）。默认 empty（无对账语义环境）。
     */
    default Optional<Instant> oldestActiveCreatedAt() {
        return Optional.empty();
    }

    /** SR §3.1 影子收口 / §4.2 对账决策的候选投影（purpose 读侧归一，无 null） */
    record ReconcileCandidate(UUID id, UUID incidentId, RcaRunState state, int generation,
                              RunPurpose purpose, Instant createdAt, Instant updatedAt,
                              Instant reconcileDeadlineAt, Instant reportingStartedAt,
                              int recoveryAttempts) {
    }

    /**
     * SR §4.1：REPORTING 进入时刻首记（幂等首触：已记不覆盖——重启/重复 advance
     * 不重置停滞计时起点）。不推进 updated_at（观测列，不当业务进展）。
     */
    default void markReportingStarted(UUID id, Instant now) {
        // 无对账语义环境（薄 fake）默认无操作；生产实现必须真写
    }

    /**
     * SR §4.1：铸点冻结对账硬期限（首记不覆盖；重试/重启不重置）。deadline 语义 =
     * 本轮调查的 SLA 期限——旧 Run 无该列值时对账只 ALERT_ONLY，不追溯制造。
     */
    default void fixReconcileDeadlineIfAbsent(UUID id, Instant deadline) {
        // 同上：生产实现必须真写
    }

    /** SR §4.3：恢复动作计数（对账触发的收尾铸造/过期各 +1；持久化上限判据） */
    default void incrementRecoveryAttempts(UUID id) {
        // 同上
    }
}
