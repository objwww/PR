#!/bin/sh
echo '--- 双网容器（eval-mgmt + alert-net）测 control-app ---'
docker run --rm --network eval-mgmt --network alert-net postgres:16-alpine sh -c \
  "for i in 1 2 3; do wget -T 5 -O- http://control-app:8080/actuator/health 2>&1 | head -2; echo ---; done"
echo '--- 双网容器的路由表（默认路由走哪）---'
docker run --rm --network eval-mgmt --network alert-net postgres:16-alpine sh -c "ip route 2>/dev/null || cat /proc/net/route | head -10"
echo '--- 双网容器解析 ---'
docker run --rm --network eval-mgmt --network alert-net postgres:16-alpine sh -c "nslookup control-app 2>&1 | tail -6"
