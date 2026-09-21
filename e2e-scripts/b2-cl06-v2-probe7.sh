#!/bin/sh
# b2-cl06-v2-probe7.sh —— PRIMARY DEAD attempts=0 深挖 + 历史同型面扫描
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
r=3564fee0-236f-4fb8-87c7-b17a197f86b4
echo "== rca_task 全列（PRIMARY）："
G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_task'"
G "select * from rca_task where run_id='$r' and task_key='PRIMARY_INVESTIGATE'"
echo "== 绑定行："
G "select task_id,role_id,role_version,round_id from rca_task_execution_binding where run_id='$r'"
echo "== 历史同型面（v1+v2 全量 run 扫描 PRIMARY DEAD attempts=0）："
G "select t.run_id||' '||ru.state||' '||ru.created_at::date from rca_task t join rca_run ru on ru.id=t.run_id where t.task_key='PRIMARY_INVESTIGATE' and t.state='DEAD' and t.attempt_count=0 order by ru.created_at desc limit 10"
echo "== 对照：PRIMARY 正常 DONE 分布 attempts："
G "select attempt_count||':'||count(*) from rca_task where task_key='PRIMARY_INVESTIGATE' and state='DONE' group by attempt_count"
