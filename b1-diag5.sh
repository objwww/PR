#!/bin/sh
# .env 去向 + 构建树近期改动时间线 + 活动度
echo "== 1) deploy 目录现状 =="
ls -la /opt/build/pr/deploy/ | head -20
echo "== 2) .env 相关一切 =="
ls -la /opt/build/pr/deploy/.env* /opt/build/.env* 2>/dev/null || echo "(全部不存在)"
echo "== 3) 构建树近 90 分钟改动（deploy 与 e2e 脚本）=="
find /opt/build/pr/deploy -mmin -90 -type f 2>/dev/null | head -10
find "/opt/build/pr/docs/测试证据/R7/e2e-脚本" -mmin -600 -type f 2>/dev/null | head -10
echo "== 4) e2e 脚本行尾检查 =="
od -c "/opt/build/pr/docs/测试证据/R7/e2e-脚本/e2e-r7-a0-provider-receipt-chain.sh" 2>/dev/null | sed -n '3p'
echo "== 5) 容器近期重建（最近 90 分钟内启动的）=="
for c in $(docker ps --format '{{.Names}}'); do
  T=$(docker inspect "$c" --format '{{.State.StartedAt}}')
  case "$T" in *T0[23]:*Z) echo "  $c StartedAt=$T";; esac
done
echo "== 6) run 面（有没有新活动）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select created_at::time(0)||' '||state from rca_run order by created_at desc limit 4;"
echo "== 7) 谁在登录（最近会话）=="
who 2>/dev/null | tail -5 || echo "(who 不可用)"
last -5 2>/dev/null | head -6 || true
echo "DIAG5-DONE"
