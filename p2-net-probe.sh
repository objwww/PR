#!/bin/sh
echo '--- 从 alert-net 容器测 control-app:8080 ---'
docker run --rm --network alert-net postgres:16-alpine sh -c \
  "wget -S -T 5 -O- http://control-app:8080/actuator/health 2>&1 | head -8" || true
echo '--- DNS 解析 ---'
docker run --rm --network alert-net postgres:16-alpine sh -c "nslookup control-app 2>&1 | tail -5" || true
echo '--- control-app 挂了哪些网 ---'
docker inspect deploy-control-app-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
