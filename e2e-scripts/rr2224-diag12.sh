#!/bin/sh
# incidentKey 大小写取证：incident 表真实键 + v1 body 文件 + e2rr 相关 incident
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===INCIDENT-TABLE===
$PG "select table_name from information_schema.tables where table_schema='public' and table_name like 'incident%'"
echo ===E2RR-INCIDENTS===
$PG "select id, incident_key, status, generation, created_at from incident where incident_key ilike '%e2rr%' order by created_at" 2>/dev/null || $PG "select column_name from information_schema.columns where table_name='incident' order by ordinal_position"
echo ===BODY-FILE-V1===
grep -o '"alertname":"[^"]*"' /opt/build/pr-logs/rr2224/v1-contaminated/body-E2RR-C429.json | head -2
echo ===BODY-FILE-V5===
grep -o '"alertname":"[^"]*"' /opt/build/pr-logs/rr2224/body-E2RR-C429.json | head -2
