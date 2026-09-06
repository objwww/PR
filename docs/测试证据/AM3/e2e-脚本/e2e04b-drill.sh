#!/bin/sh
# E2E-M3-04 崩溃恢复链 v4（tag am3-e2e04b-0906）
#   杀点A：调查 STARTED（外调中）→ docker kill control-app → 显式 start（编排器语义）
#   杀点B：报告已写 + outbox 未 SENT（K4 未确认）→ 再 kill → 显式 start
# v2 教训：docker kill 不触发 unless-stopped 重启策略（躺尸 33min 全链冻结）。
# v3 教训：旧 tag 复用会让 run 发现面命中历史行（STARTED 遗留行秒触发误杀）——
#   v4 起用全新 tag，eval_run/chaos 场景名全部无歧义。
# 用法: nohup sh cc-m330-e2e04b-drill.sh > /tmp/m330-e2e04b-drill.log 2>&1 &
set -u
TAG=am3-e2e04b-0906
q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "$1" 2>/dev/null | tr -d ' \n'; }

echo "== 预检：control-app 健康 =="
HP=$(docker exec deploy-control-app-1 wget -qO- http://127.0.0.1:8080/actuator/health 2>/dev/null | head -c 60)
echo "health=$HP"
echo "== ACTIVE chaos 会话预检 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select scenario_id, state from arena.oa_chaos_session where state='ACTIVE'"

echo "== 启动 S3 批 =="
cd /opt/build/pr
docker rm -f eval-runner-$TAG 2>/dev/null || true
nohup sh /tmp/cc-m330-evalrun.sh deploy/alert/eval/eval-scenarios-s3.yml "$TAG" \
  > /tmp/m330-$TAG-run.log 2>&1 &
sleep 5
docker ps --filter name=eval-runner-$TAG --format '{{.Names}} {{.Status}}'

echo "== 等本批 eval_run 行（新 tag 无历史歧义） =="
RUN=""
i=0
until [ "$i" -gt 100 ]; do
  RUN=$(q "select id from eval_run where dataset_version='$TAG' order by started_at desc limit 1")
  [ -n "$RUN" ] && break
  docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up || { echo "batch died early"; break; }
  sleep 5; i=$((i+1))
done
echo "RUN=$RUN"

echo "== 等本批 RCA run（锚定批 started_at 之后） =="
RR=""
i=0
until [ "$i" -gt 100 ]; do
  RR=$(q "select id from rca_run where created_at > (select started_at from eval_run where id='$RUN') order by created_at desc limit 1")
  [ -n "$RR" ] && break
  docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up || { echo "batch exited, no rca_run"; break; }
  sleep 5; i=$((i+1))
done
echo "RR=$RR"
[ -z "$RR" ] && { echo "no RR, abort kills"; exit 1; }

echo "== 杀点A：等调查 STARTED（外调中）最多 20min =="
KILL1=MISSED
i=0
until [ "$i" -gt 600 ]; do
  ST=$(q "select count(*) from rca_investigation_result where run_id='$RR' and execution_status='STARTED'" 2>/dev/null)
  if [ "${ST:-0}" -ge 1 ] 2>/dev/null; then
    docker kill deploy-control-app-1 >/dev/null 2>&1
    KILL1="FIRED at $(date +%T) started_rows=$ST"
    break
  fi
  docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up || { echo "batch exited before K1"; break; }
  sleep 2; i=$((i+1))
done
echo "KILL1=$KILL1"
echo "-- 编排器语义：显式拉起 --"
sleep 5
docker start deploy-control-app-1 >/dev/null 2>&1
sleep 20
docker ps --filter name=deploy-control-app-1 --format '{{.Names}} {{.Status}}'

echo "== 杀点B：等报告已写 + outbox 未 SENT（K4 未确认）最多 20min =="
KILL2=MISSED
i=0
until [ "$i" -gt 600 ]; do
  W=$(q "select count(*) from rca_report rr join notify_outbox o on o.report_id=rr.id where rr.run_id='$RR' and o.state <> 'SENT'" 2>/dev/null)
  if [ "${W:-0}" -ge 1 ] 2>/dev/null; then
    docker kill deploy-control-app-1 >/dev/null 2>&1
    KILL2="FIRED at $(date +%T) unconfirmed_outbox=$W"
    break
  fi
  docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up || { echo "batch exited before K2"; break; }
  sleep 1; i=$((i+1))
done
echo "KILL2=$KILL2"
sleep 5
docker start deploy-control-app-1 >/dev/null 2>&1
sleep 20
docker ps --filter name=deploy-control-app-1 --format '{{.Names}} {{.Status}}'

echo "== 等批退出（最多 30min） =="
i=0
while docker ps --filter name=eval-runner-$TAG --format '{{.Status}}' | grep -q Up; do
  [ "$i" -gt 360 ] && { echo "TIMEOUT waiting batch"; break; }
  sleep 5; i=$((i+1))
done
echo "batch exited at $(date +%T)"
exit 0
