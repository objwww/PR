#!/bin/sh
echo '--- chaos-admin 网络:'
docker inspect alert-arena-chaos-admin-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
echo '--- 各容器网络归属:'
for c in deploy-control-app-1 deploy-postgres-1 alert-order-arena-1 eval-worker-std rriso-postgres-1; do
  echo -n "$c: "
  docker inspect $c --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null
  echo ''
done
