#!/bin/sh
echo '---disk---'
df -h / | tail -1
echo '---health---'
curl -s -o /dev/null -w 'control-app=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '---workers---'
docker ps --format '{{.Names}} {{.Status}}' | grep eval
echo '---db write check---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select 'db-ok '||now();"
echo '---terminal reason---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select terminal_reason from eval_run where id::text like '12bcfacc%';"
