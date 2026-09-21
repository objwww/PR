#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
$PG "select comparable, paired_count, unpaired_count, gate_reasons::text, dimension_diffs::text from eval_comparison where baseline_run_id::text like 'aa7f25b4%' and candidate_run_id::text like '21de9287%';"
