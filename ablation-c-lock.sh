#!/bin/sh
# 消融C 续跑：锁的 FK 需要真实 op 行——先种 intent→op→lock
set -eu
cd /opt/build/pr/deploy
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
RUN=4950d39b-2196-4028-9c7b-ee8f39e793b3
RES='res://demo/checkout/ablation-c'
INTENT=c0c0c000-0000-0000-0000-00000000000c
OP1=c0c0c000-0000-0000-0000-0000000000aa
BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')

echo "=== [C-1] 种子：占锁方 intent→operation(UNKNOWN)→HELD 锁 ==="
Q "insert into action_intent(intent_id, run_id, call_seq, tool_name, tool_version, action_id, action_digest, risk, status, resolved_resource_uid, scope_snapshot, scope_snapshot_hash, resolved_at, args_json)
values ('$INTENT', '$RUN', 980003, 'chaos.resolve', '1.0.0', 'chaos.resolve', repeat('c',64), 'R2', 'OPEN', '$RES', '{\"requested_key\":\"ablation-c\"}'::jsonb, repeat('c',64), now(), '{\"target\":\"ablation-c\"}');" >/dev/null && echo "seed intent ok"
Q "insert into rca_operation(operation_id, intent_id, run_id, action_id, action_digest, resource_uid, resource_epoch, status, dry_run, params_json, prepared_at)
values ('$OP1', '$INTENT', '$RUN', 'chaos.resolve', repeat('c',64), '$RES', 7, 'UNKNOWN', true, '{}'::jsonb, now());" >/dev/null && echo "seed op ok"
Q "insert into resource_mutation_lock(resource_uid, operation_id, run_id, resource_epoch, state, ttl_expires_at, acquired_at)
values ('$RES', '$OP1', '$RUN', 7, 'HELD', now() + interval '30 minutes', now());" >/dev/null && echo "seed lock ok"
Q "select 'lock='||resource_uid||'|state='||state||'|epoch='||resource_epoch from resource_mutation_lock where resource_uid='$RES';"

echo "=== [C-2] Guardian 自动批（SAFE→APPROVED→grant） ==="
curl -s -X POST http://127.0.0.1:8080/api/mutation/auto-decide \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -d "{\"intentId\":\"$INTENT\"}"; echo

echo "=== [C-3] 有锁：plan → 期望 REJECTED LOCK_BUSY ==="
RESP_BUSY=$(curl -s -X POST http://127.0.0.1:8080/api/mutation/plan \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -d "{\"intentId\":\"$INTENT\"}")
echo "plan_with_lock=$RESP_BUSY"

echo "=== [C-4] 无锁：删锁行后同一 plan → 期望 PLANNED ==="
Q "delete from resource_mutation_lock where resource_uid='$RES' and operation_id='$OP1';" >/dev/null
echo "lock_rows_after_delete=$(Q "select count(*) from resource_mutation_lock where resource_uid='$RES';")"
RESP_OK=$(curl -s -X POST http://127.0.0.1:8080/api/mutation/plan \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -d "{\"intentId\":\"$INTENT\"}")
echo "plan_without_lock=$RESP_OK"
sleep 14
OPID=$(echo "$RESP_OK" | grep -o '"operation_id":"[^"]*"' | cut -d'"' -f4)
echo "opid=$OPID"
Q "select 'op_final='||status||'|dry_run='||dry_run from rca_operation where operation_id='$OPID';"
Q "select 'locks_left='||count(*) from resource_mutation_lock where resource_uid='$RES';"
echo "=== [C-5] 清理占锁方 UNKNOWN op（对照残留），事件/op 保留为证据 ==="
Q "select 'op1_row='||status from rca_operation where operation_id='$OP1';"
exit 0
