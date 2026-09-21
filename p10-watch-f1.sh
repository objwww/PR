#!/bin/sh
# p10 F1 窗口监视器：评测批活跃期每 15s 采样卡单 gauge 与 F1 清偿实况（时间窗过滤）
OUT=/tmp/p10-f1-watch.txt
: > "$OUT"
end=$(( $(date +%s) + 1500 ))
while [ $(date +%s) -lt $end ]; do
  STUCK=$(docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=oa_stuck_orders_current' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
print(max([float(r['value'][1]) for r in rs], default=0.0))" 2>/dev/null)
  DUP=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select coalesce(sum(n),0) from (select count(*) n from arena.oa_trade_order where correlation_id like 'chaos-eval-%' and created_at > now() - interval '40 minutes' and booking_status<>'DISCARDED' group by intent_id having count(*)>1) t" 2>/dev/null)
  DISCARDED=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select count(*) from arena.oa_trade_order where correlation_id like 'chaos-eval-%' and booking_status='DISCARDED' and discard_reason='F1_DUPLICATE' and updated_at > now() - interval '40 minutes'" 2>/dev/null)
  SESS=$(docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
    "select coalesce(string_agg(distinct state,','),'-') from arena.oa_chaos_session where created_at > now() - interval '40 minutes'" 2>/dev/null)
  echo "$(date +%H:%M:%S) stuck_gauge=$STUCK dup_groups=$DUP f1_discarded_recent=$DISCARDED sess=$SESS" >> "$OUT"
  sleep 15
done
echo FINISHED >> "$OUT"
