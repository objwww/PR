#!/bin/sh
# p12a Test A 批监视器：卡单 gauge + 自然清偿日志 + 会话/重复面（12s 采样 ×25min）
OUT=/tmp/p12a-watch.txt
: > "$OUT"
end=$(( $(date +%s) + 1500 ))
while [ $(date +%s) -lt $end ]; do
  STUCK=$(docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=oa_stuck_orders_current' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print(max([float(r['value'][1]) for r in rs], default=0.0))" 2>/dev/null)
  DUP=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select coalesce(sum(n),0) from (select count(*) n from arena.oa_trade_order where correlation_id like 'chaos-eval-%' and created_at > now() - interval '40 minutes' and booking_status<>'DISCARDED' group by intent_id having count(*)>1) t" 2>/dev/null)
  DRAIN_LOG=$(docker logs --since 3m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -c '窗口内清偿' 2>/dev/null)
  SESS=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select coalesce(string_agg(distinct fault_type||'/'||state,','),'-') from arena.oa_chaos_session where created_at > now() - interval '40 minutes'" 2>/dev/null)
  echo "$(date +%H:%M:%S) stuck=$STUCK dup_groups=$DUP drain_log3m=$DRAIN_LOG sess=$SESS" >> "$OUT"
  sleep 12
done
echo FINISHED >> "$OUT"
