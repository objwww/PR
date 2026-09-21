#!/bin/sh
echo '---worker container start time---'
docker inspect eval-worker-std --format 'started={{.State.StartedAt}} status={{.State.Status}}'
echo '---worker startup log (sweep)---'
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '孤儿|启动孤儿清扫|评测执行注册表装载' | head -4 | cut -c1-180
echo '---judge v2 4题确认---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select scenario_id, verdict, passed, total, rubric_version from eval_case_judge where eval_run_id::text like '098a2a8a%';"
