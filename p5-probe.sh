#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---inbox decision domain live---'
$PG "select decision, count(*) from alert_inbox group by decision;"
echo '---silence window hits: firing notifications matching silence rules (derive capability check)---'
$PG "select count(*) from duty_notification;"
echo '---notifications table elsewhere---'
$PG "select table_name from information_schema.tables where table_name like '%silence%' or table_name like '%suppress%';"
