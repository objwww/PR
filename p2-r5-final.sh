#!/bin/sh
sleep 300
echo '--- run-5 终态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||' | coverage='||coalesce(coverage::text,'-')||' | e2e='||coalesce(end_to_end_hit_rate::text,'-') from eval_run where id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
echo '--- 案例行 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | hit='||root_cause_hit||' | actual='||coalesce(actual_root_cause::text,'-') from eval_case_result where eval_run_id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
echo '--- 阶段全序 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select phase||' @ '||to_char(entered_at,'HH24:MI:SS') from eval_phase_event where eval_run_id='81c69fa3-e971-4531-a652-69e2b792c2e7' order by entered_at;"
echo '--- 第二轮调查与 episode 收口 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status||' | gen='||generation from incident where id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | '||state||' | '||created_at from rca_run where incident_id='f4cf44b2-a5fd-48fe-875c-69ba3f45d78a' order by created_at desc limit 2;"
echo '--- inbox（resolved 变体也应有一行）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*)||' inbox' from alert_inbox where convert_from(payload_raw,'UTF8') like '%\"alertname\":\"ArenaOrderStuck\"%';"
echo '--- 本轮回放产生的模型费用（EV-06 链）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(sum(m.cost_micros),0)/1000000.0 || ' CNY / ' || count(*) || ' calls' from rca_model_call m join eval_case_result c on c.rca_run_id = m.run_id where c.eval_run_id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
