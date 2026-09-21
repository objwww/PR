#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
R=21de9287-4c36-4663-ba64-4cdcf55e59b5
echo '---run row---'
$PG "select display_name, state, started_at, finished_at, usage_status, recovery_state from eval_run where id='$R';"
echo '---metrics summary---'
$PG "select total_scenarios, decidable_count, hit_count, coverage, conditional_accuracy, end_to_end_hit_rate from eval_run where id='$R';"
echo '---scenario spread---'
$PG "select scenario_id, count(*), sum(root_cause_hit::int) from eval_case_result where eval_run_id='$R' group by scenario_id order by scenario_id;"
echo '---judge rows all runs---'
$PG "select eval_run_id::text like '21de%', scenario_id, verdict, passed, total, rubric_version from eval_case_judge order by created_at desc limit 10;"
echo '---usage ledger rows---'
$PG "select run_id::text like '21de%', ledger_status, coalesce(total_tokens::text,'-'), coalesce(total_cost::text,'-') from eval_usage_ledger order by created_at desc limit 5;" 2>&1
