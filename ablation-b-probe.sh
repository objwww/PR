#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
Q "select 'row='||left(id::text,8)||'|'||state||'|received='||received_at||'|group='||group_key from alert_inbox where group_key like '%InjScanAblation%' order by received_at desc limit 4"
Q "select 'err_on='||coalesce(last_error::text,'-') from alert_inbox where group_key like '%InjScanAblation%' and state='QUARANTINED' order by received_at desc limit 1"
exit 0
