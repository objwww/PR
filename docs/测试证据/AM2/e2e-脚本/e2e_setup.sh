#!/bin/sh
# E2E 一次性环境准备：e2e-cli 容器（eval-mgmt + alert-net 双网）+ token + 驱动脚本。
set -e
docker pull -q python:3.12-alpine >/dev/null 2>&1 || docker pull python:3.12-alpine
docker rm -f arena-e2e-cli 2>/dev/null || true
docker run -d --name arena-e2e-cli --network eval-mgmt python:3.12-alpine sleep 14400
docker network connect alert-net arena-e2e-cli
docker exec arena-e2e-cli mkdir -p /e2e
grep '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/alert/.env > /tmp/e2e-env
chmod 600 /tmp/e2e-env
docker cp /tmp/e2e-env arena-e2e-cli:/e2e/env
rm -f /tmp/e2e-env
echo SETUP_OK
docker exec arena-e2e-cli python3 -c "print('python ok')"
