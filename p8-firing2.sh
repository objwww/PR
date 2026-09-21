#!/bin/sh
echo '--- FIRING detail:'
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
for r in d.get('data', {}).get('result', []):
    print(json.dumps(r['metric'], ensure_ascii=False))
"
echo '--- chaos session columns:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select column_name from information_schema.columns where table_schema='arena' and table_name='oa_chaos_session' order by ordinal_position" | tr '\n' ' '
echo ''
echo '--- latest chaos sessions:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select state||' '||fault_type||'@'||coalesce(target,'-')||' '||scenario_id from arena.oa_chaos_session order by id desc limit 5"
