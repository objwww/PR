#!/bin/sh
echo '--- worker 最近日志（非 drill）:'
docker logs --since 15m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -v -i drill | grep -vE '^\s*$' | tail -20
echo '--- 在飞 chaos:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select fault_type||'/'||state||' '||scenario_id from arena.oa_chaos_session where created_at > now() - interval '20 minutes'"
echo '--- FIRING:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('firing=', len(rs))
for r in rs[:8]: print(' ', r['metric'].get('alertname'))"
