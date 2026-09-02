package com.objwww.pr.control.domain.sandbox;

import java.util.Optional;
import java.util.UUID;

/**
 * SandboxJob 仓储接口（M4 §4.2 生命周期服务）。
 *
 * <p>核心操作：
 * <ul>
 *   <li>save：持久化新 PENDING 作业（INSERT）</li>
 *   <li>findById：按 ID 查询</li>
 *   <li>claimNext：领取下一个 PENDING 作业（SKIP LOCKED + 全局并发闸）</li>
 *   <li>update：更新作业状态（列级 UPDATE，lease epoch fencing）</li>
 * </ul>
 */
public interface SandboxJobRepository {

    /**
     * 持久化新 PENDING 作业（INSERT）。
     * 前置条件：对应的 tool_call 记录必须已存在（单向 FK）。
     *
     * @param job 新创建的 PENDING 作业
     */
    void save(SandboxJob job);

    /**
     * 按 ID 查询作业。
     *
     * @param jobId 作业 ID
     * @return 作业实体，不存在返回 empty
     */
    Optional<SandboxJob> findById(UUID jobId);

    /**
     * 按 tool_call_id 查询作业（单向 FK，一对一）。
     *
     * @param toolCallId 工具调用 ID
     * @return 作业实体，不存在返回 empty
     */
    Optional<SandboxJob> findByToolCallId(UUID toolCallId);

    /**
     * 领取下一个 PENDING 作业（SKIP LOCKED + 全局并发闸）。
     *
     * <p>并发安全：
     * <ul>
     *   <li>SELECT FOR UPDATE SKIP LOCKED 防止两个 claimer 领同一行</li>
     *   <li>uq_sandbox_job_inflight 部分唯一索引保证全局 LEASED ≤ 1</li>
     * </ul>
     *
     * @param leaseOwner 租约持有者标识（Broker instance + claim UUID）
     * @param leaseDurationSeconds 租约时长（秒）
     * @param workerId Broker 实例标识
     * @return 已领取的作业（状态已转 LEASED），无可领取返回 empty
     */
    Optional<SandboxJob> claimNext(String leaseOwner, int leaseDurationSeconds, String workerId);

    /**
     * 更新作业状态（列级 UPDATE，lease epoch fencing）。
     *
     * <p>Fencing：只更新 lease_epoch 匹配的行（CAS 语义），防止旧租约持有者越权写入。
     *
     * @param job 已修改的作业实体（包含新状态、结果等）
     * @param expectedEpoch 预期的 lease_epoch（仅当 DB 中 epoch == expectedEpoch 时更新成功）
     * @return true 更新成功，false epoch 不匹配（租约已失效）
     */
    boolean update(SandboxJob job, long expectedEpoch);

    /**
     * 续租：延长租约 + 更新心跳（lease epoch fencing）。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch
     * @param leaseDurationSeconds 续租时长（秒）
     * @return true 续租成功，false epoch 不匹配（租约已失效）
     */
    boolean renewLease(UUID jobId, long expectedEpoch, int leaseDurationSeconds);
}
