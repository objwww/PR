#!/bin/sh
echo '--- checkout 告警的 activeAt/value/expr ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/alerts' | python3 -c \"
import json,sys
d=json.load(sys.stdin)
for a in d['data']['alerts']:
    if a['labels'].get('alertname')=='checkout':
        print('activeAt=',a.get('activeAt'),' value=',a.get('value'))
        print('annotations=',a.get('annotations'))
\"" 2>/dev/null || true
echo '--- checkout 近1小时请求/错误 ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=sum%28rate%28checkout_requests_total%5B5m%5D%29%29' | head -c 400" || true
echo
echo '--- order-arena 是否有 stuck 注入残留（chaos-admin 场景列表）---'
docker run --rm --network eval-mgmt postgres:16-alpine sh -c "wget -qO- -T 5 http://arena-chaos-admin:8080/actuator/health 2>&1 | head -2" || true
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' stuck orders (arena)' from arena.oa_orders where status='STUCK';" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select table_schema||'.'||table_name from information_schema.tables where table_schema='arena' limit 8;"
