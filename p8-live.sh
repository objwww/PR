#!/bin/sh
echo '--- worker 最近日志:'
docker logs --since 12m eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -vE '^\s*$' | tail -12
echo '--- 在飞 chaos 会话:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select state||' '||fault_type||'@'||coalesce(target,'-')||' '||scenario_id from arena.oa_chaos_session where updated_at > now() - interval '15 minutes' order by updated_at desc limit 5"
echo '--- FIRING:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('count=', len(rs))
for r in rs[:8]:
    m = r['metric']
    print(' ', m.get('alertname'), m.get('service', ''))
"
