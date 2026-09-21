#!/bin/sh
cd /opt/build/pr/deploy
echo '--- 清 141 失败行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "delete from flyway_schema_history where version='141' and success=false;"
docker compose up migrate 2>&1 | tail -5
echo '--- 顶版 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 2;"
echo '--- 起服务 ---'
docker compose up -d control-app web 2>&1 | tail -2
sleep 40
for i in 1 2 3 4; do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  echo "health attempt$i -> $code"
  [ "$code" = "200" ] && break
  sleep 15
done
echo '--- 红队种子核验 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select dv.name||':'||dv.version||'|'||cv.case_key||'|'||dv.partition_class from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='redteam-ds' order by cv.case_key;"
echo '--- 换 worker（crafted 刺激镜像）---'
docker rm -f eval-worker-p3replay >/dev/null 2>&1 || true
sh /tmp/p2w.sh
