#!/bin/sh
echo '=== [1] 当前 chaos 流量 CREATED 滞留单（按会话分组）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select substring(correlation_id from 1) as corr,
          count(*) as created_cnt,
          min(date_trunc('minute', created_at)) as from,
          max(date_trunc('minute', created_at)) as to
     from arena.oa_trade_order
    where booking_status='CREATED' and correlation_id like 'chaos-%'
    group by correlation_id order by created_cnt desc limit 12"
echo '=== [2] 同 intent 重复组（F1 恢复该清的对象）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select intent_id, count(*) cnt,
          string_agg(booking_status||'/'||to_char(created_at,'HH24:MI'), ', ' order by created_at) states
     from arena.oa_trade_order
    where correlation_id like 'chaos-%' and booking_status<>'DISCARDED'
    group by intent_id having count(*)>1 order by cnt desc limit 8"
echo '=== [3] 单单滞留（无重复、卡 CREATED 的 chaos 单）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select t.id, t.intent_id, t.correlation_id, t.created_at
     from arena.oa_trade_order t
    where t.booking_status='CREATED' and t.correlation_id like 'chaos-%'
      and (select count(*) from arena.oa_trade_order t2
            where t2.intent_id=t.intent_id and t2.booking_status<>'DISCARDED')=1
    order by t.created_at desc limit 8"
echo '=== [4] 最近 CLOSED 的 F1 会话（对照 [1]）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'F1 '||scenario_id||' '||state||' updated='||to_char(updated_at,'MM-DD HH24:MI')
     from arena.oa_chaos_session where fault_type='F1' order by updated_at desc limit 6"
echo '=== [5] 当前 stuck gauge（Prometheus 直查）==='
docker exec -i prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=oa_stuck_orders_current' 2>/dev/null | python3 -c "
import sys, json
rs = json.load(sys.stdin).get('data', {}).get('result', [])
for r in rs: print(' oa_stuck_orders_current =', r['value'][1])
if not rs: print(' (无序列/0)')"
echo '=== [6] 注入审计：F1 恢复收口记录（repaired 实况）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select scenario_id, fault_type, detail, at
     from arena.oa_chaos_injection_audit
    where fault_type='F1' order by at desc limit 6" 2>/dev/null || \
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select table_name from information_schema.tables where table_schema='arena' and table_name like '%audit%'"
