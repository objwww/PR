#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---latest eval runs---'
$PG "select id::text like '09d4%' as mine, left(id::text,8), state, display_name, created_at from eval_run order by created_at desc limit 6;"
echo '---latest commands---'
$PG "select command_type, state, left(id::text,8), created_at from eval_run_command order by created_at desc limit 5;"
