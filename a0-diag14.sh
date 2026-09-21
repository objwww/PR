#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=e0907820-8e80-4108-b18e-0ca5d20742e7
WS=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select extract(epoch from window_start)::bigint from rca_run where id='$RUN_ID';")
WE=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select extract(epoch from window_end)::bigint from rca_run where id='$RUN_ID';")
echo "window=$WS..$WE"
echo '=== 与模型同形 query_range（checkout 计数） ==='
docker exec deploy-control-app-1 sh -c "wget -qO- --timeout=8 'http://prometheus:9090/api/v1/query_range?query=count%28%7Bservice%3D%22checkout%22%7D%29&start=$WS&end=$WE&step=60' 2>/dev/null" | head -c 300
echo ''
echo '=== up{service=checkout} 形状（模型可能写法） ==='
docker exec deploy-control-app-1 sh -c "wget -qO- --timeout=8 'http://prometheus:9090/api/v1/query?query=up%7Bservice%3D%22checkout%22%7D' 2>/dev/null" | head -c 200
echo ''
echo '=== 该窗内任一 checkout 指标实例值 ==='
docker exec deploy-control-app-1 sh -c "wget -qO- --timeout=8 'http://prometheus:9090/api/v1/query?query=count%20by%28__name__%29%28%7Bservice%3D%22checkout%22%2C__name__%3D~%22.%2B%22%7D%29' 2>/dev/null" | head -c 500
echo ''
exit 0
