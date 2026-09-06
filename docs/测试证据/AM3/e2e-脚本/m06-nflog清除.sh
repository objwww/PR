#!/bin/sh
# M3-06 修复：清除 AM nflog（repeat_interval 抑制跨重启持久化的根因）
# 顺序：运行中 rm nflog → docker kill（SIGKILL 防优雅停机把内存 nflog 重写回盘）→ start
set -u
echo "== now =="; date -u +%FT%TZ
echo "== AM args（确认 storage.path） =="
docker inspect alertmanager-am0 --format '{{.Args}}' | tr ' ' '\n' | grep -i storage || echo "no storage flag (default data/)"
echo "== AM storage files =="
docker exec alertmanager-am0 sh -c 'ls -la /alertmanager 2>/dev/null' || docker exec alertmanager-am0 sh -c 'ls -la /data 2>/dev/null'
echo "== rm nflog =="
docker exec alertmanager-am0 sh -c 'rm -f /alertmanager/nflog && echo nflog-removed; ls /alertmanager'
echo "== kill + start =="
docker kill alertmanager-am0 >/dev/null && echo killed
docker start alertmanager-am0 >/dev/null && echo started
sleep 100
echo "== now =="; date -u +%FT%TZ
echo "== AM notifications metrics =="
curl -s http://127.0.0.1:9093/metrics | grep -E '^alertmanager_notifications_total' | grep -v '^#'
echo "== AM log（错误/通知） =="
docker logs alertmanager-am0 --since 3m 2>&1 | grep -Ei 'notify|error|warn' | tail -8
echo "== inbox rows =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select count(*) from alert_inbox"
echo "== rca_run rows =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select count(*) from rca_run"
echo "== latest eval_run =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "select id, state, started_at from eval_run order by started_at desc limit 2"
echo "== batch marker =="
tail -3 /tmp/m330-waitm06.log 2>/dev/null || echo "no marker log"
