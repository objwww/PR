#!/bin/sh
# SR 部署 6：bcrypt $$ 转义修复 + 全模块包 + 热旋转修复部署 + 日志速率验证
set -e
cd /opt/build/pr/deploy
# 0) bcrypt $ 转义：compose .env 插值会把裸 $XKN... 吃掉（容器实证 hash 被截断）→ $$ 转义
if grep -q '^AUTH_OPERATOR_PASSWORD_BCRYPT=\$2b\$10\$' .env; then
  sed -i 's|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$$2b$$10$$XKNrla1h3kro4Qrr0jgSBeN2z.zo2Ok64mhwaoMoI90gOHlrKyFCu|' .env
  sed -i 's|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$$2b$$10$$XKNrla1h3kro4Qrr0jgSBeN2z.zo2Ok64mhwaoMoI90gOHlrKyFCu|' /opt/build/.env.rebuilt
  echo 'bcrypt $$ escaped'
fi
grep -c '^AUTH_OPERATOR_PASSWORD_BCRYPT=\$\$' .env
cd /opt/build
# 1) 覆盖式解包（绝不 --delete）
tar -xzf pr-sr.tar.gz -C /opt/build/pr
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests > /tmp/mvn6.log 2>&1
grep -q 'BUILD SUCCESS' /tmp/mvn6.log || { tail -20 /tmp/mvn6.log; exit 1; }
echo 'mvn BUILD SUCCESS'
ls control-app/target/*.jar | head -2
cd deploy
docker compose config --quiet 2>/tmp/cfgwarn.log && echo 'compose interpolation OK'
[ -s /tmp/cfgwarn.log ] && { echo 'WARNINGS:'; cat /tmp/cfgwarn.log; }
docker compose build control-app web 2>&1 | tail -1
docker compose up -d control-app web 2>&1 | tail -2
echo '=== 等待启动 40s ==='
sleep 40
echo '=== 容器内 bcrypt 完整性（应含 $XKNrla1h3kro 原文） ==='
docker exec deploy-control-app-1 sh -c 'env | grep AUTH_OPERATOR_PASSWORD_BCRYPT'
echo '=== health ==='
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '=== 等待 200s 观察窗（>6 个 30s 巡逻拍） ==='
sleep 200
echo '=== 观察窗内 run_reconcile_decision 日志行（期望 ~2候选/拍 × ~6拍 ≈ 6-14；热旋转 ~9000） ==='
docker logs deploy-control-app-1 --since 200s 2>&1 | grep -c run_reconcile_decision
echo '=== 启动以来 ERROR ==='
docker logs deploy-control-app-1 2>&1 | grep -c ERROR || true
echo '=== 历史 REPORTING Run 保持原样 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
  "select id||'|'||state from rca_run where state='REPORTING';"
echo '=== 部署后新代码确认（无条件 sleep：日志时间戳间隔 ~30s 抽样） ==='
docker logs deploy-control-app-1 --since 200s 2>&1 | grep run_reconcile_decision | tail -6
exit 0
