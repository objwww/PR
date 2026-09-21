#!/bin/sh
# rr-forensic4.sh —— run2 失败调用定性：error_code + 容器日志（键面 vs 模型面）
echo "== run2 调用明细（state/error_code/时间）=="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select state||'|'||coalesce(error_code,'-')||'|'||created_at from rca_model_call where run_id='26a7c89c-395f-4355-8ed3-9335bdf59473' order by created_at"
echo "== run1 对照 =="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select state||'|'||coalesce(error_code,'-') from rca_model_call where run_id='99516082-1efd-44e8-a1be-e5377ec728ca' order by created_at"
echo "== run2 终态 =="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select 'state='||state from rca_run where id='26a7c89c-395f-4355-8ed3-9335bdf59473'"
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select 'rca_task '||task_kind||' '||status from rca_task where run_id='26a7c89c-395f-4355-8ed3-9335bdf59473' limit 5" 2>/dev/null || true
echo "== control-app（K2 时代）失败/403 面 =="
docker logs rriso-control-app-1 2>&1 | grep -iE 'HTTP 40[13]|AUTH_DENIED|MODEL_FAILURE|PROTOCOL|终态失败' | tail -10
echo done
