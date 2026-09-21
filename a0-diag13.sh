#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=e0907820-8e80-4108-b18e-0ca5d20742e7
echo '=== 捕获档位 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select capture_level, count(*) from rca_model_input where model_call_id in (select id from rca_model_call where run_id='$RUN_ID') group by 1;"
echo '=== 冻结窗（run 输入面列） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select column_name from information_schema.columns where table_name='rca_run' and column_name like '%window%' or table_name='rca_run' and column_name like '%epoch%';"
echo '=== 逐调用 prompt 尾部（看模型收到什么观察） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select mi.prompt_text from rca_model_input mi join rca_model_call mc on mc.id=mi.model_call_id where mc.run_id='$RUN_ID' order by mc.action_seq desc limit 1;" | tail -c 1200
echo ''
exit 0
