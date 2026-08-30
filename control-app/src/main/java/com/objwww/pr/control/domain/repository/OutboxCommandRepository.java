package com.objwww.pr.control.domain.repository;

import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxCommand;

import java.time.Instant;

/**
 * Outbox 命令写入端口（Control 侧只有 INSERT——DB 角色不给 UPDATE 权，I10/AFT-06）。
 * Control 与 outbox_command 表的全部交互仅限：T2 同事务插入 PENDING 命令 + 依赖边。
 * 命令创建后 Control 不再触碰其状态（AFT-06），本端口刻意不提供任何状态查询/更新方法。
 */
public interface OutboxCommandRepository {

    /** 插入一条新命令（state 必须为 PENDING；operation_id 主键冲突即重投，由调用方按幂等处理） */
    void insert(OutboxCommand command);

    /** 插入一条依赖边（outbox_dependency；如 PUBLISH_REVIEW 依赖 CREATE_CHECK，REQUIRE_CONFIRMED） */
    void insertDependency(OperationId operationId, OperationId dependsOnOperationId,
                          DependencyMode mode, Instant createdAt);
}
