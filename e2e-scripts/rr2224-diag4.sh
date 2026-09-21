#!/bin/sh
# RR22 诊断第四轮：进程谱系 + 决策/收件箱/模型调用三面时间线
echo ==processes==
ps -eo pid,lstart,args | grep -E 'rr2224|rr22-iso|mock\.py' | grep -v grep
echo ==outfile-mtime==
ls -l --time-style=full-iso /tmp/rr2224.out /tmp/rr2224*.out 2>/dev/null
ls -l --time-style=full-iso /opt/build/pr-logs/rr2224/ 2>/dev/null | head -15
echo ==flock==
fuser -v /opt/build/pr-logs/rr2224/.rr2224.lock 2>&1 | head -5
fuser -v /tmp/rr2224.lock 2>&1 | head -5
PG="docker exec rriso-postgres-1 sh -c"
Q() { $PG "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -t -A -F\"|\" -c \"$1\""; }
echo ==route-decisions-2h==
Q "select id, stickiness_key, decision, run_id, created_at from canary_route_decision where created_at > now() - interval '2 hours' order by id desc limit 25"
echo ==inbox-2h==
Q "select id, created_at from alert_inbox where created_at > now() - interval '2 hours' order by created_at" 2>/dev/null || Q "select column_name from information_schema.columns where table_name='alert_inbox' order by ordinal_position"
echo ==modelcalls-per-run-2h==
Q "select run_id, count(*), string_agg(distinct state||'/'||coalesce(error_code,'-'),',') from rca_model_call where created_at > now() - interval '2 hours' group by run_id order by min(created_at)"
