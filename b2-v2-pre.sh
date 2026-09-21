#!/bin/sh
echo "== report_publication 列 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT column_name FROM information_schema.columns
WHERE table_name='report_publication' ORDER BY ordinal_position"
echo "== 昨日 PASS run 的 report/publication/事件 =="
RID=1a5175cb-2dc8-4f90-8288-40966b20311f
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT 'report', id, state, coalesce(incident_id::text,'-') FROM rca_report WHERE run_id='$RID'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT 'pub', p.id, p.state FROM report_publication p JOIN rca_report r ON p.report_id=r.id WHERE r.run_id='$RID'"
echo "== 该 run 事件类型 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT event_type, count(*) FROM rca_event WHERE run_id='$RID' GROUP BY event_type ORDER BY 2 DESC" 2>/dev/null || \
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "
SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%event%'"
echo "== loser 日志行格式（近 24h，截 6 行）==="
docker logs deploy-control-app-1 --since 24h 2>&1 | grep report_publication_loser | tail -6
echo "== 同 incident 的 winner 报告定位（该 run 的 incident）==="
INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "
SELECT incident_id FROM rca_run WHERE id='$RID'")
echo "incident=$INC"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT r.id, r.state, r.created_at FROM rca_report r WHERE r.incident_id='$INC' ORDER BY r.created_at"
