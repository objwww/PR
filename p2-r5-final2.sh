#!/bin/sh
sleep 480
echo '--- run-5 终态 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||' | coverage='||coalesce(coverage::text,'-')||' | e2e='||coalesce(end_to_end_hit_rate::text,'-')||' | unresolved='||coalesce(unresolved_rate::text,'-') from eval_run where id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
echo '--- 案例行（终）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select scenario_id||' r'||round_no||' | '||verdict||' | hit='||root_cause_hit||' | '||coalesce(failure_sample::text,'-') from eval_case_result where eval_run_id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
echo '--- registry digest（可复现元数据应含回放案例）---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select registry_digest from eval_run where id='81c69fa3-e971-4531-a652-69e2b792c2e7';"
echo '--- worker 尾日志 ---'
tail -4 /tmp/p2-worker.log
