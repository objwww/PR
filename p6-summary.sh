#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---all named eval runs summary---'
$PG "select left(id::text,8)||' | '||coalesce(display_name,'?')||' | '||state||' | '||coalesce(terminal_reason,'-')||' | '||created_at::date from eval_run where display_name is not null and display_name != '' order by created_at desc limit 12;"
echo '---metric highlights---'
$PG "select left(id::text,8)||' | 命中率='||round(end_to_end_hit_rate::numeric*100,1)||'% | F1='||coalesce(round(f1_score::numeric*100,1),'-')||'% | 覆盖='||round(coverage::numeric*100,1)||'%' from eval_run where display_name is not null and display_name != '' and end_to_end_hit_rate is not null order by created_at desc limit 6;"
