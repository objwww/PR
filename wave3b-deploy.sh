#!/bin/sh
# 波次3b：调查页签证据链 watch 修复——OVERLAY
set -e
cd /opt/build/pr
echo '87d6ec366a02a0ad13cee270cdf7eb46  /tmp/wave3b.tar.gz' | md5sum -c -
tar xzf /tmp/wave3b.tar.gz
cd deploy
docker compose build web 2>&1 | tail -1
docker compose up -d web 2>&1 | tail -1
sleep 3
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
