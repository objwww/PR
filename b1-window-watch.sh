#!/bin/sh
# B1 窗口静默侦测：构建树文件改动 + 容器重建 + 新 run 三面全静默×2 轮 → QUIET
QUIET_ROUNDS=0
i=0
while [ $i -lt 30 ]; do
  F=$(find /opt/build/pr -mmin -8 -type f 2>/dev/null | grep -v pr-logs | wc -l)
  C=$(docker ps -a --format '{{.Names}}' | while read -r c; do docker inspect "$c" --format '{{.State.StartedAt}}'; done 2>/dev/null | awk -v cut="$(date -u -d '12 minutes ago' +%Y-%m-%dT%H:%M)" '$0 > cut' | wc -l)
  R=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_run where created_at > now() - interval '15 minutes';" 2>/dev/null | tr -d ' ')
  echo "[$i $(date -u +%H:%M:%SZ)] files8m=$F containers12m=$C runs15m=$R quiet_rounds=$QUIET_ROUNDS"
  if [ "$F" -eq 0 ] && [ "$C" -eq 0 ] && [ "$R" -eq 0 ]; then
    QUIET_ROUNDS=$((QUIET_ROUNDS+1))
    [ "$QUIET_ROUNDS" -ge 2 ] && { echo "WINDOW-QUIET"; exit 0; }
  else
    QUIET_ROUNDS=0
  fi
  sleep 120
  i=$((i+1))
done
echo "WINDOW-STILL-BUSY-AFTER-60MIN"
exit 1
