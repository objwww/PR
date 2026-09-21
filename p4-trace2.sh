#!/bin/sh
echo '---control-app processing log 01:28:25-01:30:00---'
docker logs deploy-control-app-1 --since '2026-09-17T01:28:25Z' --until '2026-09-17T01:30:30Z' 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-260 | grep -iv 'hikari\|Starting\|Start completed\|Tomcat\|exposing' | tail -40
echo '---ArenaOrderStuck current episode state---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select table_name from information_schema.tables where table_name like '%incident%' or table_name like '%episode%';"
