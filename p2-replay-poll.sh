#!/bin/sh
sleep 240
echo '--- worker 是否存活 ---'
docker ps --format '{{.Names}} | {{.Status}}' | grep eval
echo '--- eed0e10d 阶段事件（期待 REPLAYING/AWAITING_RCA/SCORING）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||entered_at||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='eed0e10d-e020-4f3c-abbc-161cbc166d1a' and entered_at > '2026-09-16 20:58:00+00' order by entered_at;"
echo '--- ArenaOrderStuck 是否重投（inbox 新载荷）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' total, latest '||max(received_at)::text from alert_inbox where convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%';"
echo '--- 事故状态与新 rca_run ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation from incident where id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||state||' | '||trigger_kind||' | '||created_at from rca_run where incident_id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a' order by created_at desc limit 2;"
echo '--- worker 日志尾 ---'
tail -5 /tmp/p2-worker.log
