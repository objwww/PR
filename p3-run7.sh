#!/bin/sh
# 等 run-6 终态 → 换 worker（settle 修复镜像）→ 发起 run-7
for i in 1 2 3 4 5 6 7 8; do
  ST=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state from eval_run where id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';")
  echo "run6=$ST (poll $i)"
  [ "$ST" != "RUNNING" ] && break
  sleep 60
done
echo '--- run-6 终态案例 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict from eval_case_result where eval_run_id='daf6f7bd-41b5-484a-aa38-f0608cbc855f';"
echo '--- 事故是否已 RESOLVED（下轮 prev gate 前提）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation from incident where incident_key like 'alertname=ArenaOrderStuck%' order by episode_started_at desc limit 1;"
echo '--- 换 worker（settle 修复镜像）---'
docker rm -f eval-worker-p3replay >/dev/null 2>&1 || true
sh /tmp/p2w.sh
