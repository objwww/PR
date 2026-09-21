#!/bin/sh
echo '--- checkout 事故终态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||status||' | gen='||generation||' | started='||episode_started_at from incident where incident_key like 'alertname=checkout%' order by episode_started_at desc limit 1;"
echo '--- Prometheus checkout 告警详情（activeAt/value）---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/alerts'" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for a in d['data']['alerts']:
    if a['labels'].get('alertname')=='checkout':
        print('severity=',a['labels'].get('severity'),' activeAt=',a.get('activeAt'),' value=',a.get('value'))
"
