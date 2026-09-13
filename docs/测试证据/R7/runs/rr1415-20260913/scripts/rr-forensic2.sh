#!/bin/sh
# rr-forensic2.sh —— run1 模型调用 FAILED 定位 + RR15-B 现场日志
echo "== main log 全文 =="
cat /opt/build/pr-logs/rr1415-main.log
echo "== rr15b-app.log 尾部（本次 placeholder 车现场）=="
tail -30 /opt/build/pr-logs/rr1415/rr15b-app.log 2>/dev/null
echo "== rr15b-app.log 拒启动 grep =="
grep -c '拒绝启动' /opt/build/pr-logs/rr1415/rr15b-app.log 2>/dev/null
echo "== V48 rca_model_call 错误面列 =="
grep -nE 'error|terminal|fail' /opt/build/pr/control-app/src/main/resources/db/migration/V48__r7a1_rca_model_call.sql | head -8
echo "== run1 模型调用明细（脱敏：不查请求体）=="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select state, coalesce(left(coalesce(route_id,'-'),40),'-'), requested_model from rca_model_call where run_id='388d9a4d-bc0b-4eaa-882d-1c836bd7655a' order by created_at"
echo "== run1 终态 =="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select id||' '||status||' '||coalesce(finished_at::text,'-') from rca_run where id='388d9a4d-bc0b-4eaa-882d-1c836bd7655a'" 2>/dev/null || docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select column_name from information_schema.columns where table_name='rca_run' and column_name like '%state%' or table_name='rca_run' and column_name='status'"
echo "== control-app 日志中 litellm 报错面 =="
docker logs rriso-control-app-1 2>&1 | grep -iE 'litellm|401|403|unauthorized|model.*fail|OpenAI' | tail -8
echo done
