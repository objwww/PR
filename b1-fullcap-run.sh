#!/bin/sh
# B1-1 主事件：run26 配方复现（FULL 捕获在位）
# 1) flagd paymentFailure=100% + 等 checkout 错误行  2) 驱动 A0 run  3) 等终态
set -u
echo "== A) 故障注入（run26-prep）=="
sh /opt/build/run26-prep.sh || { echo "PREP_FAIL——中止"; exit 1; }

echo "== B) 记录起点时间并驱动 A0 =="
START_TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
echo "run_start_marker=$START_TS" | tee /tmp/b1fullcap-start.txt
mkdir -p /opt/build/pr-logs
cd /opt/build/pr/docs/测试证据/R7/e2e-脚本 || exit 1
nohup sh -c '. /opt/build/r7-operator-env.sh && sh e2e-r7-a0-provider-receipt-chain.sh' \
    > /opt/build/pr-logs/b1-fullcap-a0.log 2>&1 &
echo "a0-launched pid=$!"

echo "== C) 轮询新 run 至终态（最长 12 分钟）=="
i=0
RUN_ID=""
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
[ -n "$RUN_ID" ] || { echo "WARN: 12 分钟未见终态（run 可能仍在跑——后面手动收）"; }
echo "RUN_ID=$RUN_ID" >> /tmp/b1fullcap-start.txt
echo "== D) A0 日志尾部 =="
tail -20 /opt/build/pr-logs/b1-fullcap-a0.log | cut -c1-180
echo "RUN-DRIVER-DONE"
