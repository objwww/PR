#!/bin/sh
echo '=== [B2] F1 恢复收口审计（join 会话取 scenario）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select s.scenario_id, a.fault_type, left(coalesce(a.detail::text,'-'),70) detail,
          to_char(a.occurred_at,'MM-DD HH24:MI') at
     from arena.oa_injection_audit a
     join arena.oa_chaos_session s on s.id=a.session_id
    where a.fault_type='F1' and a.action='RECOVERED'
    order by a.occurred_at desc limit 10"
echo '=== [F] t9s21 会话（模糊查 correlation 前缀）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select fault_type||' '||scenario_id||' '||state from arena.oa_chaos_session where scenario_id like '%s21%' limit 6"
echo '=== [G] 25 张 CREATED 滞留单的 correlation 前缀分布 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select regexp_replace(correlation_id, '-[0-9]+$', '') as session_prefix, count(*)
     from arena.oa_trade_order
    where booking_status='CREATED' and correlation_id like 'chaos-%'
    group by 1 order by count(*) desc limit 10"
echo '=== [H] 这些滞留单有无重复 intent（跨 DISCARDED 也算）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'dup_intents='||count(distinct t.intent_id)
     from arena.oa_trade_order t
    where t.booking_status='CREATED' and t.correlation_id like 'chaos-%'
      and (select count(*) from arena.oa_trade_order t2
            where t2.intent_id=t.intent_id)=1"
