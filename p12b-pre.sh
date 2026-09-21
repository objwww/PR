#!/bin/sh
RUN=348e9ba5-2926-4114-b279-6066209f206a
echo '--- Test A 批:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'run='||state from eval_run where id='$RUN'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'case '||scenario_id||' '||verdict||' hit='||root_cause_hit from eval_case_result where eval_run_id='$RUN' order by scenario_id"
echo '--- FIRING:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('firing_count=', len(rs))
for r in rs[:6]: print(' ', r['metric'].get('alertname'))"
echo '--- 清窗（Test B 前置）:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'chaos='||count(*) from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'eval_inflight='||count(*) from eval_run where state in ('RUNNING','SCORING','PENDING')"
