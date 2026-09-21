#!/bin/sh
echo '=== S3 重复单的支付记录与状态取证 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select t.correlation_id, t.booking_status, t.pay_status, t.created_at,
          (select count(*) from arena.oa_payment_record p where p.order_id=t.id) as pay_rows,
          (select string_agg(p.kind||'/'||p.result,',') from arena.oa_payment_record p where p.order_id=t.id) as pay_kinds
     from arena.oa_trade_order t
    where t.correlation_id like 'chaos-eval-p10160311-s3-r1%' order by t.correlation_id"
