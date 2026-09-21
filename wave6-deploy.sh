#!/bin/sh
set -e
cd /opt/build/pr
echo '86eca2e095b2d386577b800793fae067  /tmp/wave6.tar.gz' | md5sum -c -
tar xzf /tmp/wave6.tar.gz
cd deploy
docker compose build web 2>&1 | tail -1
docker compose up -d web 2>&1 | tail -1
sleep 3
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
