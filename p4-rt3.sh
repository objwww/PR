#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---report raw_text head---'
$PG "select left(raw_text,600) from rca_report where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';"
echo '---tool_invocation count---'
$PG "select 'tool_invocations='||count(*) from rca_tool_invocation where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';" 2>&1
echo '---attempt statuses---'
$PG "select status from rca_attempt where run_id='e19efae5-379e-4ba6-94b2-86f618312b21';"
