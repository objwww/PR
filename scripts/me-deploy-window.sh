#!/usr/bin/env bash
# M-e 部署窗脚本（本地 Git Bash 执行，驱动 195）——门禁→打包→部署→冒烟四段
# 门禁（任一不过即退出，不碰 195）：
#   G1 Jev 在途五类测试本地全绿（并行会话收敛信号）
#   G2 flyway 无 V 版撞号（V160 只许一个文件）
#   G3 195 无 RUNNING/ACCEPTED/QUEUED eval_run（不杀活批=worker_lost 纪律）
# 部署：源码打包（排除 .git/target/node_modules/dist/大文件）→ 195 备份 → 覆盖
#   /opt/build/pr → mvn package → compose build+up control-app web → 冒烟。
# 冒烟：health/flyway V158+V160(or V161)/behavior 端点/safety 五态字段/web chunk 锚文本。
set -euo pipefail

SSH="ssh -i ~/.ssh/id_ed25519 -o ConnectTimeout=20 root@146.56.195.225"
SCP="scp -i ~/.ssh/id_ed25519 -o ConnectTimeout=20"
TS=$(date +%Y%m%d%H%M)
echo "=== [G1] Jev 在途五类测试"
cd /e/kimiCode
mvn -o -pl control-app -am '-Dtest=JevEnhancementServiceTest,JevSelectionAssemblyTest,HttpJevClientTest,SecurityConfigTest,ControlContextSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | grep -E "Tests run:.*(Failures|Errors)" | tail -3
# 上面 grep 无 Failures: [1-9] / Errors: [1-9] 才算过（mvn 失败本身会 set -e 中止）
echo "=== [G2] flyway 撞号检查"
DUP=$(ls control-app/src/main/resources/db/migration/ | sed 's/__.*//' | sort | uniq -d)
if [ -n "$DUP" ]; then echo "G2 FAIL: 撞号 $DUP ——先改名再发"; exit 2; fi
ls control-app/src/main/resources/db/migration/ | tail -4
echo "=== [G3] 195 活批检查"
ACTIVE=$($SSH 'docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "SELECT count(*) FROM eval_run WHERE state IN ('"'"'RUNNING'"'"','"'"'ACCEPTED'"'"','"'"'QUEUED'"'"');"' 2>/dev/null | grep -vE "post-quantum|store now|openssh.com" | tr -d '[:space:]')
if [ "$ACTIVE" != "0" ]; then echo "G3 FAIL: 195 有 $ACTIVE 个活批——不杀批，顺延"; exit 3; fi
echo "门禁全过，进入部署段"

echo "=== [D1] 本地打包"
PKG=/tmp/me-deploy-$TS.tar.gz
tar -czf "$PKG" \
  --exclude=.git --exclude=target --exclude=node_modules --exclude=dist \
  --exclude='*.tar.gz' --exclude=diag2.tar.gz --exclude=.idea --exclude='*.log' \
  pom.xml shared-kernel control-app alert-web deploy
ls -la "$PKG"

echo "=== [D2] 195 备份+覆盖"
$SCP "$PKG" root@146.56.195.225:/tmp/ 2>/dev/null | grep -vE "post-quantum|store now|openssh.com" || true
$SSH "
set -e
cd /opt/build
rm -f /tmp/pr-backup-me-*.tar.gz
tar -czf /tmp/pr-backup-me-$TS.tar.gz --exclude=.git --exclude=target --exclude=node_modules --exclude=dist -C /opt/build pr/pom.xml pr/shared-kernel pr/control-app pr/alert-web pr/deploy 2>/dev/null || true
df -h / | tail -1
tar -xzf /tmp/me-deploy-$TS.tar.gz -C /opt/build/pr --strip-components=0
echo synced
" 2>/dev/null | grep -vE "post-quantum|store now|openssh.com"

echo "=== [D3] 195 构建（mvn package + compose build）"
$SSH "
set -e
export JAVA_HOME=/opt/jdk-21.0.12.1+1
cd /opt/build/pr
mvn -q -pl control-app -am -DskipTests package 2>&1 | tail -5
cd deploy
docker compose build control-app web 2>&1 | tail -3
docker compose up -d control-app web 2>&1 | tail -3
" 2>/dev/null | grep -vE "post-quantum|store now|openssh.com"

echo "=== [S1] 冒烟：health + flyway"
sleep 30
$SSH "
for i in 1 2 3 4 5 6; do
  H=\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)
  [ \"\$H\" = 200 ] && break; sleep 15
done
echo health=\$H
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc \"SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;\"
" 2>/dev/null | grep -vE "post-quantum|store now|openssh.com"

echo "=== [S2] 冒烟：behavior/safety 端点 + web 锚文本"
$SSH '
TOK=$(docker exec deploy-control-app-1 printenv APP_OPERATOR_API_BEARER)
curl -s -c /tmp/jarme http://127.0.0.1:8080/api/auth/csrf -o /dev/null
X=$(grep XSRF /tmp/jarme | awk "{print \$7}")
RUN=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "SELECT id FROM eval_run WHERE state='"'"'SUCCEEDED'"'"' ORDER BY finished_at DESC LIMIT 1;")
echo "run=$RUN"
echo "-- behavior:"
curl -s -b /tmp/jarme -H "X-XSRF-TOKEN: $X" -H "Authorization: Bearer $TOK" "http://127.0.0.1:8080/api/eval/runs/$RUN/behavior" | head -c 300; echo
echo "-- safety 五态字段:"
curl -s -b /tmp/jarme -H "X-XSRF-TOKEN: $X" -H "Authorization: Bearer $TOK" "http://127.0.0.1:8080/api/eval/runs/$RUN/safety" | head -c 400; echo
echo "-- web:"
curl -s -o /dev/null -w "web=%{http_code}\n" http://127.0.0.1:8090/
IDX=$(curl -s http://127.0.0.1:8090/ | grep -o "assets/index-[^\"]*\.js" | head -1)
echo "chunk=$IDX"
for A in 全部计划轮次成功率 证据行为; do
  C=$(docker exec deploy-web-1 sh -c "grep -l \"$A\" /usr/share/nginx/html/assets/*.js 2>/dev/null | head -1")
  [ -n "$C" ] && echo "ANCHOR_OK: $A ($C)" || echo "ANCHOR_MISS: $A"
done
' 2>/dev/null | grep -vE "post-quantum|store now|openssh.com"

echo "=== 部署窗完成。后续（agent 驱动）：evalw.sh 重启 worker → 发 30 轮批 → 监视"
