#!/bin/sh
echo '=== [1] 手动实验会话收口（正门 off）==='
cd /opt/build/pr/deploy/alert
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
docker exec -i deploy-postgres-1 sh -c "wget -qO- --header='X-Admin-Token: $TOKEN' --header='Content-Type: application/json' --post-data='{\"scenarioId\":\"chaos-manual-p10-drain\"}' http://arena-chaos-admin:8080/chaos/F1/off" || echo 'off 已过或会话已终态'
echo ''
echo '=== [2] 清理实验单据（零残留）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "delete from arena.oa_trade_order where correlation_id like 'chaos-manual-p10-drain-%'"
echo '=== [3] 会话终态确认 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'manual-session state='||state from arena.oa_chaos_session where scenario_id='chaos-manual-p10-drain'"
echo '=== [4] p10 批终态 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='6564c6a6-5829-43b8-a76d-b8f849276ded'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' hit='||root_cause_hit from eval_case_result where eval_run_id='6564c6a6-5829-43b8-a76d-b8f849276ded' order by scenario_id"
echo '=== [5] 当前 FIRING（应只有基线）==='
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('firing_count=', len(rs))
for r in rs[:6]: print(' ', r['metric'].get('alertname'))"
