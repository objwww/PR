#!/bin/sh
echo '=== [1] 容器内 jar 是否含新代码字符串 ==='
docker exec alert-order-arena-1 sh -c "grep -c '窗口内清偿' /app/app.jar" 2>&1
echo '=== [2] 容器启动时间 vs 部署时间 ==='
docker inspect alert-order-arena-1 --format 'started={{.State.StartedAt}} image={{.Image}}' | cut -c1-60
echo '=== [3] SQL 手动验证：造两单同 intent（50s/45s 龄）跑 findF1YoungDuplicates 原句 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent <<'EOF'
INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,quantity,amount,
  booking_status,pay_status,created_at,enabled_at,updated_at)
VALUES
  (gen_random_uuid(),'p10-manual-intent-1','chaos-p10-manual-a','b','sku-std',1,100.00,'CREATED','NOT_PAY', now()-make_interval(secs=>50),NULL,now()),
  (gen_random_uuid(),'p10-manual-intent-1','chaos-p10-manual-b','b','sku-std',1,100.00,'CREATED','NOT_PAY', now()-make_interval(secs=>45),NULL,now());
WITH ranked AS (
    SELECT t.id, t.intent_id, t.booking_status, t.pay_status, t.correlation_id,
           row_number() OVER (PARTITION BY t.intent_id ORDER BY t.created_at, t.id) AS rn
    FROM arena.oa_trade_order t
    WHERE t.booking_status <> 'DISCARDED'
      AND t.correlation_id LIKE 'chaos-%'
      AND ('chaos-p10-manual' IS NULL OR t.correlation_id LIKE 'chaos-p10-manual' || '%')
)
SELECT r.correlation_id, r.rn, r.booking_status
FROM ranked r
JOIN (SELECT intent_id FROM ranked GROUP BY intent_id HAVING count(*) > 1) d
  ON d.intent_id = r.intent_id
WHERE r.rn > 1 AND r.booking_status = 'CREATED'
  AND EXISTS (SELECT 1 FROM arena.oa_trade_order t2
               WHERE t2.id = r.id
                 AND t2.created_at < now() - make_interval(secs => 40));
DELETE FROM arena.oa_trade_order WHERE correlation_id LIKE 'chaos-p10-manual-%';
EOF
echo '=== [4] p10160311-s3 会话的 target 值 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c \
  "select 'target=['||target||'] state='||state from arena.oa_chaos_session where scenario_id='chaos-eval-p10160311-s3-r1'"
