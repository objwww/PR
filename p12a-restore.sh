#!/bin/sh
echo '=== [1] 还原 paymentFailure=off（并行会话常态基线）==='
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'off'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
echo '=== [2] Test A 批终态 ==='
RUN=348e9ba5-2926-4114-b279-6066209f206a
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='$RUN'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' tool='||coalesce(tool_calls_total,-1)||' hit='||root_cause_hit from eval_case_result where eval_run_id='$RUN' order by scenario_id"
echo '=== [3] FIRING/残留 ==='
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('firing_count=', len(rs))
for r in rs[:6]: print(' ', r['metric'].get('alertname'))"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(state||':'||scenario_id,', '),'(无)') from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')"
