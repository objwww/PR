#!/bin/sh
echo '=== [1] 手插 F1 ACTIVE 会话 + 两张超龄重复单 ==='
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent <<'EOF'
INSERT INTO arena.oa_chaos_session(id,scenario_id,fault_type,target,ttl_seconds,operator,
  config_digest,state,generation,created_at,expires_at,updated_at)
VALUES (gen_random_uuid(),'chaos-manual-p10-drain','F1','chaos-manual-p10-drain',300,
  'p10-manual','manual','ACTIVE',1,now(),now()+make_interval(secs=>300),now());
INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,quantity,amount,
  booking_status,pay_status,created_at,enabled_at,updated_at)
VALUES
  (gen_random_uuid(),'p10-manual-drain-i1','chaos-manual-p10-drain-a','b','sku-std',1,100.00,'CREATED','NOT_PAY', now()-make_interval(secs=>50),NULL,now()),
  (gen_random_uuid(),'p10-manual-drain-i1','chaos-manual-p10-drain-b','b','sku-std',1,100.00,'CREATED','NOT_PAY', now()-make_interval(secs=>45),NULL,now());
EOF
echo '=== [2] 等 15s（3 个扫描循环）==='
sleep 15
echo '--- order-arena 日志:'
docker logs --since 1m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '窗口内清偿|恢复收口' | tail -4
echo '--- 单据状态:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select correlation_id, booking_status, discard_reason from arena.oa_trade_order where correlation_id like 'chaos-manual-p10-drain%' order by correlation_id"
echo '=== [3] 再等 15s 复查 ==='
sleep 15
docker logs --since 2m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '窗口内清偿|恢复收口' | tail -4
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select correlation_id, booking_status, discard_reason from arena.oa_trade_order where correlation_id like 'chaos-manual-p10-drain%' order by correlation_id"
