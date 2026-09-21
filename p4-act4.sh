#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---the one inbox row payload head---'
$PG "select convert_from(substring(payload_raw from 1 for 700),'UTF8') from alert_inbox where received_at > now() - interval '30 minutes' order by received_at desc limit 1;"
