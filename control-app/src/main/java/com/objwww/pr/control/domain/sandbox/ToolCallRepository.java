package com.objwww.pr.control.domain.sandbox;

import java.util.Optional;
import java.util.UUID;

/**
 * ToolCall 仓储接口（M4 §4.1 工具调用账本）。
 *
 * <p>核心操作：
 * <ul>
 *   <li>save：持久化新 RUNNING 工具调用（INSERT）</li>
 *   <li>findById：按 ID 查询</li>
 *   <li>update：更新工具调用状态（列级 UPDATE，lease epoch fencing）</li>
 * </ul>
 */
public interface ToolCallRepository {

    /**
     * 持久化新 RUNNING 工具调用（INSERT）。
     *
     * @param toolCall 新创建的 RUNNING 工具调用
     */
    void save(ToolCall toolCall);

    /**
     * 按 ID 查询工具调用。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具调用实体，不存在返回 empty
     */
    Optional<ToolCall> findById(UUID toolCallId);

    /**
     * 更新工具调用状态（列级 UPDATE，lease epoch fencing）。
     *
     * <p>Fencing：只更新 lease_epoch 匹配的行（CAS 语义），防止旧租约持有者越权写入。
     *
     * @param toolCall 已修改的工具调用实体（包含新状态、观测等）
     * @param expectedEpoch 预期的 lease_epoch（仅当 DB 中 epoch == expectedEpoch 时更新成功）
     * @return true 更新成功，false epoch 不匹配（租约已失效）
     */
    boolean update(ToolCall toolCall, long expectedEpoch);
}
