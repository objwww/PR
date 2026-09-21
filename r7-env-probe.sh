#!/bin/sh
# 操作员 env 自检（只显长度不显值）
. /opt/build/r7-operator-env.sh
echo "webhook_bearer_len=${#R7_WEBHOOK_BEARER}"
echo "release_bearer_len=${#R7_RELEASE_BEARER}"
echo "pg_url_shape=$(echo "$R7_PG_URL" | grep -c 'postgresql://postgres:')"
echo "runs_dir=$R7_RUNS_DIR"
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)
echo "health_no_auth=$code"
exit 0
