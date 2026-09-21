#!/bin/sh
echo '=== r348e9ba5 s3 重复单取证 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select t.correlation_id, t.booking_status, t.pay_status, t.created_at, t.updated_at,
          extract(epoch from (t.updated_at-t.created_at))::int as life_s,
          (select count(*) from arena.oa_payment_record p where p.order_id=t.id) as pay_rows,
          (select string_agg(p.kind||'/'||p.result,',') from arena.oa_payment_record p where p.order_id=t.id) as pay_kinds,
          t.discard_reason
     from arena.oa_trade_order t
    where t.correlation_id like 'chaos-eval-r348e9ba52926-s3-r1%' order by t.correlation_id"
echo '=== 处理失败告警（本批窗口）==='
docker logs --since 40m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -c 'chaos 会话处理失败'
echo '=== flagd 当前 paymentFailure（再次确认生效中）==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select 'na'"
