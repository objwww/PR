#!/bin/sh
echo '--- 所有 eval 相关容器 ---'
docker ps --format '{{.Names}} | {{.Status}} | {{.Image}}' | grep -i eval || echo none
echo '--- 本 run 已写入的案例行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | '||coalesce(failure_sample_json::text,'-') from eval_case_result where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
echo '--- eval_run_command 状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select command_type||' | '||state||' | claimed='||coalesce(claimed_at::text,'-') from eval_run_command where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a';"
echo '--- 陈旧 worker 的镜像与配置（看它的 registry/dataset）---'
for C in $(docker ps --format '{{.Names}}' | grep -i 'eval' | grep -v p2replay); do
  echo "== $C =="
  docker inspect $C --format '{{.Created}} | {{.Config.Image}}'
  docker inspect $C --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -o 'eval-worker-[a-z0-9-]*\|dataset-version[^,"]*' | head -3
done
