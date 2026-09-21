#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---rca_report models (recent)---'
$PG "select model, count(*) from rca_report where created_at > '2026-09-18T00:00:00Z' group by model order by 2 desc limit 5;"
echo '---pending/running eval commands---'
$PG "select command_type, state, created_at from eval_run_command where created_at > '2026-09-18T17:00:00Z' order by created_at desc limit 6;"
echo '---running runs---'
$PG "select display_name, state from eval_run where state='RUNNING';"
echo '---litellm model groups---'
MK=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')
docker run --rm --network alert-net curlimages/curl:latest -s http://litellm-am3:4000/v1/models -H "Authorization: Bearer $MK" 2>/dev/null | head -c 600
