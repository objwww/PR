#!/bin/sh
echo '=== [A] t9s21 会话是什么故障 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select fault_type||' '||scenario_id||' '||state from arena.oa_chaos_session where scenario_id like '%t9s21%' limit 5"
echo '=== [B] F1 恢复收口审计实况 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select scenario_id, fault_type, left(coalesce(detail,'-'),60) detail, to_char(created_at,'MM-DD HH24:MI') at
     from arena.oa_injection_audit where fault_type='F1' order by created_at desc limit 10" 2>&1
echo '=== [C] 审计表结构（防列名错）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select column_name from information_schema.columns where table_schema='arena' and table_name='oa_injection_audit' order by ordinal_position" | tr '\n' ' '
echo ''
echo '=== [D] 全部 chaos CREATED 滞留单计数（不分窗口）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'chaos_created_stuck='||count(*) from arena.oa_trade_order where booking_status='CREATED' and correlation_id like 'chaos-%'"
echo '=== [E] 全部 chaos 非废单滞留分布 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select booking_status, pay_status, count(*), min(to_char(created_at,'MM-DD')) oldest
     from arena.oa_trade_order where correlation_id like 'chaos-%' and booking_status<>'DISCARDED'
    group by booking_status, pay_status order by count(*) desc"
