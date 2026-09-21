#!/bin/sh
echo '--- checkout 事故终态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||status||' | gen='||generation||' | started='||episode_started_at from incident where id='49ea2bf5' or incident_key like 'alertname=checkout%' order by episode_started_at desc limit 1;"
echo '--- Prometheus 是否仍有 checkout 告警 ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/alerts'" | tr ',' '\n' | grep -c '"alertname":"checkout"' || echo 0
echo '--- 站台健康 ---'
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
docker ps --format '{{.Names}} {{.Status}}' | grep deploy
