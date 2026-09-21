#!/bin/sh
# PAGE 批 195 部署前预检：树状态、告警面健康、eval worker 存在性
cd /opt/build/pr || exit 1
echo "== tree =="
ls -d alert-web control-app shared-kernel deploy 2>/dev/null
ls alert-web/src/api 2>/dev/null
echo "== PAGE 批是否已在（EvalLaunchGate 应为 0） =="
ls control-app/src/main/java/com/objwww/pr/control/eval/application/ | grep -c EvalLaunchGate || true
echo "== control-app health =="
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo "== web =="
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
echo "== flyway top =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version || '|' || success from flyway_schema_history where success order by installed_rank desc limit 1"
echo "== eval worker 进程面（是否有 eval profile 常驻） =="
docker ps --format '{{.Names}}' | grep -i eval || echo "no eval container"
docker exec deploy-control-app-1 sh -c 'env | grep -c EVAL' 2>/dev/null || echo "control-app 无 EVAL env"
echo "== 现有 LAUNCH 命令行（不改动） =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from eval_run_command where command_type='LAUNCH'"
echo "== 现有 drill（只读，取一条 id 供详情验证） =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(max(id::text),'none') from drill_job"
