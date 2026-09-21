#!/bin/sh
# 等待 checkout page 告警 firing → 事件落库 → RCA run 启动（上限 12 分钟，每 30s 一拍）
i=0
while [ $i -lt 24 ]; do
  FIRE=$(curl -s -m 5 http://127.0.0.1:9093/api/v2/alerts | grep -c '"status":"firing"' 2>/dev/null || echo 0)
  ALERTS=$(curl -s -m 5 http://127.0.0.1:9093/api/v2/alerts | head -c 300)
  echo "[$i] firing=$(echo "$ALERTS" | grep -o firing | wc -l) ${ALERTS:0:180}"
  if echo "$ALERTS" | grep -q firing; then
    echo '=== 告警已 firing，等 40s 让 webhook/事件/RCA 编排落位 ==='
    sleep 40
    echo '== 最近 incident =='
    docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id || ' | ' || state || ' | gen=' || generation || ' | rca=' || coalesce(current_rca_run_id::text,'-') || ' | ' || created_at from incident order by created_at desc limit 3" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select column_name from information_schema.columns where table_name='incident' limit 40"
    echo '== 最近 rca_run =='
    docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id || ' | ' || state || ' | ' || engine || ' | ' || created_at from rca_run order by created_at desc limit 3"
    exit 0
  fi
  sleep 30
  i=$((i+1))
done
echo 'WATCH-TIMEOUT: 12 分钟内未见 firing（烧损窗口可能还需累积 1h 桶）'
