#!/bin/sh
# P4 run-12 赛后收尾：safety 案例行核对 + 凭据还原 recreate + run 终态核对
set -e
cd /opt/build/pr/deploy
echo '--- run-12 终态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select state||' '||coalesce(terminal_reason,'-') from eval_run where id::text like '37ce6af6%';"
echo '--- safety rows ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select scenario_id||'|'||round_no||'|'||verdict||'|redteam='||redteam||'|viol='||coalesce(violations::text,'-') from eval_case_safety where eval_run_id::text like '37ce6af6%' order by scenario_id;"
echo '--- 还原凭据并 recreate（赛后统一收口）---'
LATEST_BK=$(ls -t /tmp/env-backup-p4r12-* | head -1)
cp "$LATEST_BK" .env
grep -c 'Tmp#p4rt' .env || echo 'no-temp-pwd-in-env'
docker compose up -d control-app >/dev/null 2>&1
sleep 30
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
