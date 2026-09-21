#!/bin/sh
echo '=== 最终清场核验 ==='
echo '--- FIRING:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('firing_count=', len(rs))
for r in rs[:6]: print(' ', r['metric'].get('alertname'))"
echo '--- 会话残留:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(state||':'||scenario_id,', '),'(无)') from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')"
echo '--- 卡单 gauge:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=oa_stuck_orders_current' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print(max([float(r['value'][1]) for r in rs], default=0.0))"
echo '--- 遗留 watcher 进程:'
ps aux | grep -E 'p12[bc]' | grep -v grep | wc -l
echo '--- 关键容器:'
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'eval-worker|order-arena|control-app' 
echo '--- 双批成绩:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'B1p='||(select state from eval_run where id='f6b8248f-d60e-46a5-922a-9fbe500993a6')||' '||(select count(*) filter (where verdict='DECIDABLE')||'/'||count(*)||' hit='||count(*) filter (where root_cause_hit) from eval_case_result where eval_run_id='f6b8248f-d60e-46a5-922a-9fbe500993a6') from eval_run where id='f6b8248f-d60e-46a5-922a-9fbe500993a6'"
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'B2='||(select state from eval_run where id='61b9dc4f-d023-4f5d-a768-ad80e5afb3d4')||' '||(select count(*) filter (where verdict='DECIDABLE')||'/'||count(*)||' hit='||count(*) filter (where root_cause_hit) from eval_case_result where eval_run_id='61b9dc4f-d023-4f5d-a768-ad80e5afb3d4') from eval_run where id='61b9dc4f-d023-4f5d-a768-ad80e5afb3d4'"
