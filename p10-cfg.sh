#!/bin/sh
echo '=== [1] 容器环境/命令行配置 ==='
docker inspect alert-order-arena-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -iE 'arena|scan|interval' 
docker inspect alert-order-arena-1 --format '{{json .Config.Cmd}}'
docker inspect alert-order-arena-1 --format '{{json .Config.Entrypoint}}'
echo '=== [2] 宿主源码 scan-interval 默认值 ==='
grep -n 'scan-interval-ms' /opt/build/pr/order-arena/src/main/java/com/objwww/pr/arena/ArenaConfig.java
echo '=== [3] compose 里 order-arena 的 environment ==='
sed -n '/order-arena:/,/alert-promtail/p' /opt/build/pr/deploy/alert/docker-compose.yml | grep -E 'interval|SCAN|environment|APP_' | head -8
echo '=== [4] p10160311-s3 被废单的创建/废单时刻（复算窗口）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select correlation_id, created_at, updated_at, extract(epoch from (updated_at-created_at))::int as drained_after_s from arena.oa_trade_order where correlation_id like 'chaos-eval-p10160311-s3-r1%' and booking_status='DISCARDED' order by correlation_id"
