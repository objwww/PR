#!/bin/sh
# rr1415-restart.sh —— 杀残留 runner + rriso 清场 + 单发重启
pkill -f 'rr1415-ru[n]' 2>/dev/null
sleep 3
pgrep -f 'rr1415-ru[n]' >/dev/null && echo "STILL-ALIVE" || echo "KILL-CLEAN"
cd /opt/build/pr/rr-iso
docker compose -p rriso -f docker-compose.iso.yml --env-file rr-iso.env down -v >/dev/null 2>&1
docker ps -a --filter name=rriso --format '{{.Names}}' | head -3
echo "down 完成，重新发射"
rm -f /opt/build/pr-logs/rr1415-main.log
nohup sh /opt/build/rr1415-run.sh > /opt/build/pr-logs/rr1415-main.log 2>&1 &
echo "RELAUNCHED pid=$!"
