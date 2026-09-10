#!/bin/sh
# 一次性拉取 gatus v5.17.0 镜像并记录 digest（证据落 /tmp/gatus-evidence/）
mkdir -p /tmp/gatus-evidence
{
  echo "=== docker pull twinproduction/gatus:v5.17.0 ==="
  date -u
  docker pull twinproduction/gatus:v5.17.0
  echo "=== docker images --digests (gatus) ==="
  docker images --digests | grep -i gatus
  echo "=== inspect RepoDigests ==="
  docker image inspect twinproduction/gatus:v5.17.0 --format '{{index .RepoDigests 0}}'
} > /tmp/gatus-evidence/pull.log 2>&1
echo DONE_PULL
cat /tmp/gatus-evidence/pull.log
