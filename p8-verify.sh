#!/bin/sh
# p8 终验：eval_case_result.tool_calls_total 对账两个真源
RUN=52f84916-58bf-4eb5-9f05-90cc4220eeec
echo '=== 案例工具计数 vs rca_tool_invocation 账本 vs rca_evidence 证据面 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select c.scenario_id as sc,
          c.verdict,
          c.tool_calls_total as scored,
          (select count(*) from rca_tool_invocation t where t.run_id=c.rca_run_id) as ledger,
          (select count(*) from rca_evidence e where e.run_id=c.rca_run_id) as evid
     from eval_case_result c
    where c.eval_run_id='$RUN'
    order by c.scenario_id, c.round_no"
echo '=== 汇总 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'cases='||count(*)||
          ' scored_tool_nonzero='||count(*) filter (where tool_calls_total>0)||
          ' decidable='||count(*) filter (where verdict='DECIDABLE')||
          ' hit='||count(*) filter (where root_cause_hit)
     from eval_case_result where eval_run_id='$RUN'"
echo '=== rca_run 终态分布 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select r.state||'='||count(*) from rca_run r join eval_case_result c on c.rca_run_id=r.id where c.eval_run_id='$RUN' group by r.state"
