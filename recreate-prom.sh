#!/bin/sh
# 只重建 prometheus-am0（mem_limit 300m→768m）。迭代补缺失必需变量（warning 不算错）。
cd /opt/build/pr/deploy/alert
export LITELLM_DB_PASSWORD=x
export LITELLM_MASTER_KEY=x
i=0
while [ $i -lt 12 ]; do
  i=$((i+1))
  if docker compose --env-file /opt/build/pr/deploy/.env -p alert config --quiet 2>/tmp/cc.err; then
    echo "interpolation-ok"
    break
  fi
  VAR=$(grep -oE 'required variable [A-Za-z0-9_]+' /tmp/cc.err | awk '{print $3}' | head -1)
  if [ -z "$VAR" ]; then
    echo "UNEXPECTED:"; cat /tmp/cc.err
    exit 1
  fi
  echo "dummy: $VAR"
  eval "export $VAR=x"
done
docker compose --env-file /opt/build/pr/deploy/.env -p alert up -d --no-deps prometheus 2>&1 | tail -2
sleep 14
docker stats --no-stream --format '{{.MemUsage}}' prometheus-am0
