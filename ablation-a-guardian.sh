#!/bin/sh
# 消融A：Guardian 低危白名单 有 vs 无 对比（真机，全程 bearer 运行时提取不回显）
set -eu
cd /opt/build/pr/deploy
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
HEALTH() { for i in $(seq 1 40); do s=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true); [ "$s" = "200" ] && { echo "health=200 after ${i}x3s"; return 0; }; sleep 3; done; echo "health_timeout=$s"; return 1; }
RUN_ID=4950d39b-2196-4028-9c7b-ee8f39e793b3

echo "=== [A-ON] 白名单开启（现状） ==="
echo "container_whitelist=$(docker exec deploy-control-app-1 sh -c 'echo ${APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS:-<empty>}')"

Q "insert into action_intent(intent_id, run_id, call_seq, tool_name, tool_version, action_id, action_digest, risk, status, resolved_resource_uid, scope_snapshot, scope_snapshot_hash, resolved_at, args_json)
values ('a1a10000-0000-0000-0000-00000000000a', '$RUN_ID', 980001, 'chaos.resolve', '1.0.0', 'chaos.resolve', repeat('a',64), 'R2', 'OPEN', 'res://demo/checkout/ablation-a-on', '{\"requested_key\":\"ablation-a-on\"}'::jsonb, repeat('a',64), now(), '{\"target\":\"ablation-a-on\"}');" >/dev/null && echo "seed A1 ok"

BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')
RESP1=$(curl -s -X POST http://127.0.0.1:8080/api/mutation/auto-decide \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -d '{"intentId":"a1a10000-0000-0000-0000-00000000000a"}')
echo "auto_decide_A1=$RESP1"
echo "$RESP1" > /tmp/ablation-a-on-resp.json

Q "select 'request='||request_id||'|'||state from approval_request where intent_id='a1a10000-0000-0000-0000-00000000000a';"
Q "select 'decision='||approver_id||'|'||approver_role||'|'||decision from approval_decisions where request_id=(select request_id from approval_request where intent_id='a1a10000-0000-0000-0000-00000000000a');"
Q "select 'grant='||grant_id||'|'||state from approval_grant where request_id=(select request_id from approval_request where intent_id='a1a10000-0000-0000-0000-00000000000a');"
Q "select 'event='||payload->>'verdict'||'|'||payload->>'reason' from rca_event where event_type='GUARDIAN_REVIEWED' and payload::text like '%a1a10000-0000-0000-0000-00000000000a%' order by seq desc limit 1;"

echo "=== [A-OFF] 白名单移除 ==="
cp .env /tmp/env-ablation-a-backup
sed -i 's/^APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS=.*/APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS=/' .env
grep -c '^APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS=$' .env
docker compose up -d control-app 2>&1 | tail -1
HEALTH
echo "container_whitelist=$(docker exec deploy-control-app-1 sh -c 'echo ${APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS:-<empty>}')"

Q "insert into action_intent(intent_id, run_id, call_seq, tool_name, tool_version, action_id, action_digest, risk, status, resolved_resource_uid, scope_snapshot, scope_snapshot_hash, resolved_at, args_json)
values ('a1a10000-0000-0000-0000-00000000000b', '$RUN_ID', 980002, 'chaos.resolve', '1.0.0', 'chaos.resolve', repeat('a',64), 'R2', 'OPEN', 'res://demo/checkout/ablation-a-off', '{\"requested_key\":\"ablation-a-off\"}'::jsonb, repeat('a',64), now(), '{\"target\":\"ablation-a-off\"}');" >/dev/null && echo "seed A2 ok"

RESP2=$(curl -s -X POST http://127.0.0.1:8080/api/mutation/auto-decide \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  -d '{"intentId":"a1a10000-0000-0000-0000-00000000000b"}')
echo "auto_decide_A2=$RESP2"
echo "$RESP2" > /tmp/ablation-a-off-resp.json

Q "select 'request_count_for_A2='||count(*) from approval_request where intent_id='a1a10000-0000-0000-0000-00000000000b';"
Q "select 'event='||payload->>'verdict'||'|'||payload->>'reason' from rca_event where event_type='GUARDIAN_REVIEWED' and payload::text like '%a1a10000-0000-0000-0000-00000000000b%' order by seq desc limit 1;"

echo "=== [A-RESTORE] 还原白名单 ==="
cp /tmp/env-ablation-a-backup .env
grep '^APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS=' .env
docker compose up -d control-app 2>&1 | tail -1
HEALTH
echo "container_whitelist_restored=$(docker exec deploy-control-app-1 sh -c 'echo ${APP_ALERT_MUTATION_GUARDIAN_LOW_RISK_TOOLS:-<empty>}')"
exit 0
