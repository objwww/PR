#!/bin/sh
# p10: FUP-04(a) F1 窗口内清偿部署（清窗检查 → 落源码 → 构建 → 只重建 order-arena）
set -e
PR=/opt/build/pr
cd "$PR"

echo '== [0/5] 清窗检查（并行会话保护）=='
BUSY=0
A=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')")
echo "chaos_active_recovering=$A"; [ "$A" = "0" ] || BUSY=1
E=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from eval_run where state in ('RUNNING','SCORING','PENDING')")
echo "eval_inflight=$E"; [ "$E" = "0" ] || BUSY=1
D=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select count(*) from drill_job where state not in ('CLOSED','FAILED','DONE','RECOVERY_FAILED','CANCELLED')" 2>/dev/null || echo UNKNOWN)
echo "drill_inflight=$D"; [ "$D" = "0" ] || BUSY=1
if [ "$BUSY" != "0" ]; then echo 'WINDOW_BUSY — 中止部署'; exit 8; fi
echo '(清窗通过)'

echo '== [1/5] 落源码 =='
rm -rf /tmp/p10-files && mkdir -p /tmp/p10-files
tar xzf /tmp/p10-batch.tar.gz -C /tmp/p10-files
find /tmp/p10-files -type f | sort
# tar 只含本批 4 文件（相对路径齐备），cp -r 目录合并落位，不触碰并行会话文件
cp -r /tmp/p10-files/order-arena "$PR/"
grep -c 'f1DrainGraceSeconds' "$PR/order-arena/src/main/java/com/objwww/pr/arena/application/chaos/ChaosRecoveryService.java"

echo '== [2/5] mvn package order-arena =='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl order-arena -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -4

echo '== [3/5] docker build order-arena =='
cd "$PR/deploy/alert"
docker compose build order-arena 2>&1 | grep -E 'naming|ERROR' | head -3

echo '== [4/5] 重建 order-arena 容器 =='
docker compose up -d order-arena 2>&1 | tail -2

echo '== [5/5] 启动验证 =='
sleep 25
docker ps --format '{{.Names}} {{.Status}}' | grep order-arena
docker logs --since 2m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'Started|ERROR' | tail -3
df -h / | tail -1
echo P10_DEPLOY_OK
