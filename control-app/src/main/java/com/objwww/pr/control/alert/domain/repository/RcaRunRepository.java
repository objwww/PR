package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;

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
}
