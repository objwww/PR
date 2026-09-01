# M2 人工修复审批 Runbook

> 适用范围：仅审批 `policy_tier=MANUAL` 且 `state=PENDING` 的 `repair_request`。
> 本文不授权直接改 Outbox、资源状态或策略档；执行前必须由业务负责人确认远端对象确实需要重建。

## 1. 审批前只读核对

在 `psql` 中先设置待审批 ID，并核对资源、PR 世代和当前状态：

```sql
\set repair_request_id '替换为 repair_request.id'

SELECT rr.id, rr.policy_tier, rr.state, rr.attempt_count, rr.max_attempts,
       r.resource_type, r.remote_id, r.state AS resource_state,
       s.repository_full_name, s.pr_number,
       o.pr_revision_id, s.current_revision_id
  FROM repair_request rr
  JOIN publication_resource r ON r.id = rr.publication_resource_id
  JOIN outbox_command o ON o.operation_id = r.created_by_operation_id
  JOIN pr_subject s ON s.id = r.pr_subject_id
 WHERE rr.id = :'repair_request_id'::uuid;
```

必须同时满足：`MANUAL / PENDING / resource_state=MISSING / pr_revision_id=current_revision_id`。任一不满足都停止，不得绕过 Planner 的世代 gate。

## 2. 幂等审批事务

执行人、理由和事件 UUID 都必须显式提供；禁止空 actor/reason。重复执行时，只有第一次能把 `PENDING` 推进到 `APPROVED`，后续更新 0 行。

```sql
\set ON_ERROR_STOP on
\set repair_request_id '替换为 repair_request.id'
\set approval_event_id '替换为新生成的 UUID'
\set actor '替换为审批人账号'
\set reason '替换为审批理由或工单号'

BEGIN;

-- 空审计字段主动失败并回滚。
SELECT CASE
         WHEN length(trim(:'actor')) > 0 AND length(trim(:'reason')) > 0 THEN 1
         ELSE 1 / 0
       END AS approval_fields_valid;

WITH approved AS (
    UPDATE repair_request
       SET state = 'APPROVED',
           approved_by = :'actor',
           approved_at = now(),
           approval_reason = :'reason',
           updated_at = now()
     WHERE id = :'repair_request_id'::uuid
       AND policy_tier = 'MANUAL'
       AND state = 'PENDING'
    RETURNING id, publication_resource_id
), origin AS (
    SELECT a.id AS request_id, o.review_run_id, o.pr_revision_id
      FROM approved a
      JOIN publication_resource r ON r.id = a.publication_resource_id
      JOIN outbox_command o ON o.operation_id = r.created_by_operation_id
)
INSERT INTO execution_event(
    event_id, review_run_id, pr_revision_id, event_type, schema_version,
    correlation_id, producer, payload, occurred_at)
SELECT :'approval_event_id'::uuid, review_run_id, pr_revision_id,
       'REPAIR_APPROVED', 1, review_run_id, 'manual-runbook',
       jsonb_build_object('repair_request_id', request_id,
                          'approved_by', :'actor',
                          'reason', :'reason'),
       now()
  FROM origin;

COMMIT;
```

审批后必须查询到审计三列齐全，且只出现一条 `REPAIR_APPROVED`：

```sql
SELECT state, approved_by, approved_at, approval_reason
  FROM repair_request WHERE id = :'repair_request_id'::uuid;

SELECT count(*)
  FROM execution_event
 WHERE event_type = 'REPAIR_APPROVED'
   AND payload->>'repair_request_id' = :'repair_request_id';
```

期望：`APPROVED` 且计数为 `1`。之后只等待 `RepairPlanner` 收敛，不手工插入/更新 `outbox_command`。

## 3. 异常与恢复

- 状态不是 `PENDING`：事务更新 0 行；先查明是否已批准、已派发或已终态，不重复干预。
- PR 已换届：不批准；Planner 也会以 `EXPIRED` fail-closed。
- 审批后长期未派发：检查 control 日志、CAS 中最新 desired payload、`last_error/next_attempt_at`；禁止直接补 Outbox。
- 命令进入 `MANUAL/FAILED_TERMINAL`：按事件和 `last_error` 排障，重新铸单必须走新的业务决策，不复活终态 repair_request。
- 取证只保留 UUID、状态、错误码和时间；不得复制 token、Authorization 或完整敏感响应头。
