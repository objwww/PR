#!/bin/sh
set -e
cd /tmp && tr -d '\r' < diag34c-web.tar.md5 | md5sum -c -
tar xf /tmp/diag34c-web.tar -C /opt/build/pr
cd /opt/build/pr/deploy
docker compose build web 2>&1 | tail -1
docker compose up -d web >/dev/null
sleep 8
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
