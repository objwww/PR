#!/bin/sh
# 收官：回滚 prompt 覆写至原姿态（保留 prometheus 修复）+ 汇总七跑证据
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 25
echo '=== 姿态复核 ==='
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT || echo prompt-override-removed'
docker exec deploy-control-app-1 sh -c 'env | grep APP_ALERT_R7_PRIMARY_ENABLED'
echo '=== 七跑日志与 runs 归档清单 ==='
ls -la /opt/build/pr-logs/a0-r7batch3-run*.log 2>/dev/null
ls /opt/build/runs-r7batch3/
tar -czf /opt/build/r7batch3-evidence.tar.gz -C /opt/build pr-logs/a0-r7batch3-run1.log pr-logs/a0-r7batch3-run2.log pr-logs/a0-r7batch3-run3.log pr-logs/a0-r7batch3-run4.log pr-logs/a0-r7batch3-run5.log pr-logs/a0-r7batch3-run6.log pr-logs/a0-r7batch3-run7.log runs-r7batch3 pr-logs/a0-diag-none 2>/dev/null || tar -czf /opt/build/r7batch3-evidence.tar.gz -C /opt/build pr-logs/a0-r7batch3-run1.log pr-logs/a0-r7batch3-run2.log pr-logs/a0-r7batch3-run3.log pr-logs/a0-r7batch3-run4.log pr-logs/a0-r7batch3-run5.log pr-logs/a0-r7batch3-run6.log pr-logs/a0-r7batch3-run7.log runs-r7batch3
echo archive-done
exit 0
