#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---gate reasons for aa7f25b4 vs 21de9287---'
$PG "select comparable||' | paired='||paired_count||' unpaired='||unpaired_count||' | reasons='||coalesce(gate_reasons,'-')||' | dims='||coalesce(dimension_diffs,'-') from eval_comparison where baseline_run_id::text like 'aa7f25b4%' and candidate_run_id::text like '21de9287%';" 2>&1 | head -6
