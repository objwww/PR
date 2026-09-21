#!/bin/sh
sleep 210
echo '--- run-3 状态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||' | '||coalesce(terminal_reason,'-') from eval_run where id='2fbc70d9-d12b-4908-a54b-26c3e3b1d41b';"
echo '--- 阶段事件 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||to_char(entered_at,'HH24:MI:SS')||' || '||coalesce(detail::text,'-') from eval_phase_event where eval_run_id='2fbc70d9-d12b-4908-a54b-26c3e3b1d41b' order by entered_at;"
echo '--- ArenaOrderStuck 重投确认（inbox 新载荷）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' total, latest '||max(received_at)::text from alert_inbox where convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%';"
echo '--- 事故状态/新 run ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation from incident where id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||state||' | '||trigger_kind||' | '||created_at from rca_run where incident_id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a' order by created_at desc limit 2;"
echo '--- 案例行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | hit='||root_cause_hit from eval_case_result where eval_run_id='2fbc70d9-d12b-4908-a54b-26c3e3b1d41b';"
echo '--- worker 尾日志 ---'
tail -6 /tmp/p2-worker.log
