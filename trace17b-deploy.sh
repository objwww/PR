#!/bin/sh
# trace17b：仅前端瀑布条配色修复（web 容器重建）
set -e
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
cd /tmp && tr -d '\r' < trace17b-web.tar.md5 | md5sum -c -
cd /opt/build/pr
tar xf /tmp/trace17b-web.tar -C /opt/build/pr
cd deploy
docker compose build web 2>&1 | tail -1
docker compose up -d web 2>&1 | tail -1
sleep 8
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
curl -s http://127.0.0.1:8090/ | grep -oE 'assets/RunDetailView-[A-Za-z0-9_-]+\.js' | head -1 || true
