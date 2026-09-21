#!/bin/sh
docker inspect eval-worker-p2replay --format '{{json .NetworkSettings.Networks}}' | head -c 600
echo
echo '--- 从容器内直接发一个测试载荷（用 postgres 镜像在两个网上各测一次）---'
for NET in eval-mgmt alert-net; do
  echo "== $NET =="
  docker run --rm --network $NET postgres:16-alpine sh -c \
    "wget -T 5 -O- http://control-app:8080/actuator/health 2>&1 | head -2" || echo "(不可达)"
done
