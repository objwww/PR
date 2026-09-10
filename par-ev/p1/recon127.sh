#!/bin/sh
# 只读侦察 127：既有 gatus / python3 / 空闲端口 / docker 版本
{
  echo "=== DATE ==="
  date -u
  echo "=== DOCKER PS -A (gatus only) ==="
  docker ps -a | grep -i gatus || echo "(no gatus container)"
  echo "=== DOCKER IMAGES (gatus only) ==="
  docker images --digests | grep -i gatus || echo "(no gatus image)"
  echo "=== DOCKER VERSION ==="
  docker --version
  docker compose version 2>&1 || echo "(no compose plugin)"
  echo "=== PYTHON3 ==="
  python3 --version 2>&1 || echo "(no python3)"
  echo "=== NC ==="
  which nc ncat 2>&1 || echo "(no nc)"
  echo "=== LISTEN PORTS (ss -tlnp) ==="
  ss -tlnp
  echo "=== /opt/gatus-contract-test exists? ==="
  ls -la /opt/gatus-contract-test 2>&1 || echo "(not exists)"
  echo "=== /opt listing ==="
  ls -la /opt
  echo "=== DF /opt ==="
  df -h /opt
} > /tmp/gatus-recon.txt 2>&1
echo DONE_RECON
