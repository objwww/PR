#!/bin/bash
source /tmp/ma-drill-lib.sh
TS=$(date +%H%M%S); SCEN="ma-t9-s22-$TS"
echo "======== S22 消息重复消费（F15）========"
echo "[on] $(chaos_on F15 "$SCEN" 900 ArenaDuplicateFulfillment F15 ticket ma-t9-s22)"
sleep 10
OID=$(create_order "chaos-t9s22-$TS-1"); echo "[create] $OID"
echo "[pay] $(pay_order "$OID" "chaos-t9s22-$TS-1")"
echo "[wait 40s：消费循环(10s)先插 attempt1，F15 命中再插 attempt2]"
sleep 40
echo "[gauge] $(gauge oa_duplicate_fulfillments_current)"
echo "[alerts]"; alerts ArenaDuplicateFulfillment
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's22_attempts(期望2)='||count(*) from arena.oa_fulfillment_attempt a join arena.oa_fulfillment_order f on f.id=a.fulfillment_id join arena.oa_trade_order t on t.id=f.trade_order_id where t.correlation_id like 'chaos-t9s22-$TS-%'"
echo "[off] $(chaos_off F15 "$SCEN")"
wait_closed "$SCEN" 8 oa_duplicate_fulfillments_current
echo "[alerts_final]"; alerts ArenaDuplicateFulfillment
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's22_attempts_residual(期望1)='||count(*) from arena.oa_fulfillment_attempt a join arena.oa_fulfillment_order f on f.id=a.fulfillment_id join arena.oa_trade_order t on t.id=f.trade_order_id where t.correlation_id like 'chaos-t9s22-$TS-%'"
audit_of "$SCEN"
