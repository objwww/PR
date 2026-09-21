#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---run cd682643 judge rows full---'
$PG "select scenario_id, round_no, verdict, passed, total, left(error,180) as err from eval_case_judge where eval_run_id::text like 'cd682643%' order by scenario_id, round_no;"
echo '---all judge errors across runs (distribution)---'
$PG "select coalesce(left(error,120),'no-error') as err, count(*) from eval_case_judge where verdict='ERROR' group by 1 order by 2 desc limit 10;"
