#!/bin/sh
# B1-1 主事件 v2：正确的前置门（payment 失败行 + 指标面 error 系列）→ 驱动 A0 → 等终态
set -u
echo "== A) 指标面：error_type=UNKNOWN 系列（prometheus-am0）=="
docker exec prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%7D' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
rs=d['data']['result']
print('series:',len(rs))
for s in rs[:5]:
    m=s['metric']; print(' ',m.get('service_name','?'),m.get('rpc_method','?'),'=',s['value'][1])" 2>/dev/null || echo "(prom 查询失败)"
echo "== B) 日志面门：payment 失败行数（5m）=="
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query?query=sum(count_over_time(%7Bservice_name%3D%22payment%22%7D%7C%7C~%22Payment+request+failed%22%5B5m%5D))' 2>/dev/null | head -c 260
echo
echo "== C) 驱动 A0 =="
mkdir -p /opt/build/pr-logs
cd /opt/build/pr/docs/测试证据/R7/e2e-脚本 || exit 1
nohup sh -c '. /opt/build/r7-operator-env.sh && sh e2e-r7-a0-provider-receipt-chain.sh' \
    > /opt/build/pr-logs/b1-fullcap-a0.log 2>&1 &
echo "a0-launched pid=$!"
echo "== D) 轮询新 run 至终态（最长 12 分钟）=="
i=0; RUN_ID=""
while [ $i -lt 72 ]; do
  sleep 10
  ROW=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c \
    "select id::text||'|'||state from rca_run where created_at > now() - interval '15 minutes' order by created_at desc limit 1;")
  case "$ROW" in
    "") echo "  [$i] （尚无新 run）" ;;
    *SUCCEEDED*|*FAILED*|*CANCELLED*|*DEAD*|*TIMEOUT*) echo "  [$i] 终态: $ROW"; RUN_ID="${ROW%%|*}"; break ;;
    *) echo "  [$i] $ROW" ;;
  esac
  i=$((i+1))
done
echo "RUN_ID=$RUN_ID" > /tmp/b1fullcap-run.txt
echo "== E) A0 日志尾部 =="
tail -25 /opt/build/pr-logs/b1-fullcap-a0.log | cut -c1-170
echo "RUN-DRIVER2-DONE"
