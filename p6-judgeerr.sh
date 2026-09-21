#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---judge error distribution---'
$PG "select verdict, coalesce(left(error,150),'-') as err, count(*) from eval_case_judge group by 1,2 order by 3 desc limit 8;"
echo '---judge by scenario---'
$PG "select scenario_id, verdict, count(*) from eval_case_judge group by 1,2 order by 1,2;"
echo '---usage table find---'
$PG "select table_name from information_schema.tables where table_name like '%usage%';"
