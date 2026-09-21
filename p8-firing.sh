#!/bin/sh
echo '--- FIRING alerts now:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
rs = d.get('data', {}).get('result', [])
print('count=', len(rs))
for r in rs[:12]:
    m = r['metric']
    print(' ', m.get('alertname'), m.get('service_name', m.get('job', '')))
"
echo '--- chaos sessions (latest 5):'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select state||' '||fault_type||'@'||coalesce(target,'-')||' '||scenario_id from arena.oa_chaos_session order by started_at desc limit 5"
