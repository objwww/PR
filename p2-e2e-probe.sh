#!/bin/sh
echo '=== 1. 候选全列（含来源 run/report）==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id||'|'||state||'|'||case_key||'|'||source_run_id||'|'||coalesce(source_feedback_id::text,'-') from rca_regression_candidate order by created_at;"
echo '=== 2. 已入集案例的 payload（GT 形态参照）==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select cv.case_key||' => '||cv.payload::text from case_version cv limit 1;"
echo '=== 3. 各候选来源 run 的 incident 与状态 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select c.case_key||' | incident='||r.incident_id||' | state='||r.state from rca_regression_candidate c join rca_run r on r.id=c.source_run_id order by c.created_at;"
echo '=== 4. 对应 incident 的 alertname/status ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select distinct c.case_key||' | '||i.incident_key||' | '||i.status from rca_regression_candidate c join rca_run r on r.id=c.source_run_id join incident i on i.id=r.incident_id order by 1;"
echo '=== 5. alert_inbox 冻结载荷（按候选 alertname 粗筛）==='
for AN in $(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select distinct substring(i.incident_key from 'alertname=([^|]+)') from rca_regression_candidate c join rca_run r on r.id=c.source_run_id join incident i on i.id=r.incident_id;"); do
  CNT=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from alert_inbox where state in ('PROCESSED','IGNORED','RECEIVED') and convert_from(payload_raw,'UTF8') like '%\"alertname\":\"$AN\"%' and convert_from(payload_raw,'UTF8') like '%\"status\":\"firing\"%';")
  echo "alertname=$AN firing载荷=$CNT"
done
echo '=== 6. eval_run 最近 dataset_version 取值参照 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select dataset_version||'|'||count(*) from eval_run group by dataset_version order by 2 desc limit 3;"
echo '=== 7. API 闸门 dataset 白名单（control-app env）==='
docker exec deploy-control-app-1 env | grep -i 'EVAL_DATASET\|LAUNCH' || echo '(默认 eval-ds-1)'
