#!/bin/bash
# 守望：6h 比率/checkout 告警/drill2 状态，任一落定即退出
for i in $(seq 1 200); do
  V=$(curl -s "http://127.0.0.1:9090/api/v1/query" --data-urlencode "query=slo:sli_error:ratio_rate6h" | python3 -c "
import json,sys
rs=json.load(sys.stdin)['data']['result']
print(round(float(rs[0]['value'][1]),4) if rs else 9)")
  F=$(curl -s http://127.0.0.1:9090/api/v1/alerts | python3 -c "
import json,sys
print(sum(1 for a in json.load(sys.stdin)['data']['alerts'] if a['labels'].get('alertname')=='checkout' and a['state']=='firing'))")
  D=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "SELECT state FROM drill_job WHERE id='ebca6944-00fc-4d1c-a836-5866f04afab2'")
  echo "$(date +%H:%M:%S) 6h=$V checkout_firing=$F drill2=$D"
  [ "$F" = "0" ] && { echo CLEARED; break; }
  case "$D" in CLOSED*) echo DRILL2_CLOSED; break;; esac
  sleep 60
done
