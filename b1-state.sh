#!/bin/sh
# FULL 捕获窗被冲后现状核查
echo "== 1) control-app INPUTCAPTURE =="
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i INPUTCAPTURE || echo "INPUTCAPTURE-GONE（对方 02:31 重建冲掉）"
echo "== 2) 我的 override 文件 =="
ls -la /opt/build/b1-fullcap-override.yml 2>/dev/null || echo "(不在)"
echo "== 3) 构建树近 10 分钟文件改动 =="
find /opt/build/pr -mmin -10 -type f 2>/dev/null | grep -v pr-logs | head -5 || true
echo "(空=静默)"
echo "== 4) 近 30 分钟新 run 数 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
  "select count(*) from rca_run where created_at > now() - interval '30 minutes';"
echo "== 5) 当前 UTC =="
date -u +%H:%M:%SZ
