#!/bin/sh
echo '=== 配对案例输入摘要对比（B1p vs B2，同场景同轮）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select c.scenario_id||' r'||c.round_no as sc,
          left(i.input_digest,16) as digest16,
          length(i.input_digest) as dlen
     from eval_case_input i
     join eval_case_result c on c.rca_run_id = i.rca_run_id
    where i.eval_run_id in ('f6b8248f-d60e-46a5-922a-9fbe500993a6','61b9dc4f-d023-4f5d-a768-ad80e5afb3d4')
      and c.scenario_id='S3' and c.round_no=1
    order by i.eval_run_id" 2>&1
echo '=== eval_case_input 列名 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select string_agg(column_name,',') from information_schema.columns where table_name='eval_case_input'"
echo '=== B2 的自动对比较 baseline 是谁 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'baseline='||baseline_run_id||' candidate='||candidate_run_id from eval_comparison where candidate_run_id='61b9dc4f-d023-4f5d-a768-ad80e5afb3d4'"
