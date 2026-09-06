#!/bin/sh
# E2E-M3-06 batch-b · AM harness 放宽 + 清 nflog + 启动批 am3-e2e06b-0906
# 根因链（台账素材）：AM nflog 持久化（repeat 4h 跨重启）+ 同指纹同代际去重 →
#   批1 两轮 run_not_found。铺路：批1 退出后 resolve 已投递（incident RESOLVED@gen8）。
# 本脚本：group_interval 5m→10s（drill 临时放宽，事后恢复）→ 清 nflog（rm+SIGKILL 防回写）→
#   确认 litellm 停机、无 ACTIVE chaos 会话 → 起批。
set -eu
TAGB=am3-e2e06b-0906
CFG=/opt/build/pr/deploy/alert/alertmanager/alertmanager.yml

echo "== 前置检查 =="
ACT=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select count(*) from arena.oa_chaos_session where state='ACTIVE'")
echo "ACTIVE chaos sessions=$ACT"
INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select status from incident where id='5552d241-77f4-4bcd-abeb-35f3f2f1a90d'")
echo "incident 5552d241=$INC (需 RESOLVED)"
docker ps -a --filter name=litellm-am3 --format '{{.Names}} {{.Status}}'
docker run --rm --network alert-ab curlimages/curl:8.8.0 -s "http://echo-receiver:8080/__admin/requests" \
  > /tmp/m330-e2e06b-echo-before.json 2>/dev/null || echo '[]' > /tmp/m330-e2e06b-echo-before.json

echo "== AM 配置放宽（drill 临时） =="
sed -i 's/^  group_interval: 5m$/  group_interval: 10s/' "$CFG"
grep -nE 'group_wait|group_interval|repeat_interval' "$CFG"
echo "== 清 nflog（rm + SIGKILL 防优雅停机回写）→ start（顺带加载新配置） =="
docker exec alertmanager-am0 rm -f /alertmanager/nflog
docker kill alertmanager-am0 >/dev/null
docker start alertmanager-am0 >/dev/null
sleep 8
curl -s http://127.0.0.1:9093/api/v2/status | python3 -c "
import json,sys
c=json.load(sys.stdin)['config']['original']
for ln in c.splitlines():
    if any(k in ln for k in ('group_wait','group_interval','repeat_interval')): print('runtime:',ln.strip())
"

echo "== 启动 batch-b（litellm 保持停机） =="
docker stop litellm-am3 2>/dev/null || true
rm -f /tmp/m330-waitm06b.log
docker rm -f "eval-runner-$TAGB" 2>/dev/null || true
cd /opt/build/pr
nohup sh /tmp/cc-m330-evalrun.sh deploy/alert/eval/eval-scenarios-s3.yml "$TAGB" \
  > "/tmp/m330-$TAGB-run.log" 2>&1 &
nohup sh -c "while docker ps --format '{{.Names}}' | grep -q 'eval-runner-$TAGB'; do sleep 15; done; echo M06B_BATCH_DONE >> /tmp/m330-waitm06b.log" \
  > /dev/null 2>&1 &
sleep 12
docker ps --filter name="eval-runner-$TAGB" --format '{{.Names}} {{.Status}}'
date -u +%FT%TZ
