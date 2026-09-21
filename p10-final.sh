#!/bin/sh
sleep 30
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print('firing_count=', len(rs))
for r in rs[:6]: print(' ', r['metric'].get('alertname'), r['metric'].get('alertstate'))"
echo '--- chaos 残留会话:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select coalesce(string_agg(state||':'||scenario_id, E'\n'),'(无)') from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')"
