#!/bin/bash
source /tmp/ma-drill-lib.sh
TS=$(date +%H%M%S); SCEN="ma-t9-s20-$TS"
echo "======== S20 库存超卖（F13）========"
echo "[on] $(chaos_on F13 "$SCEN" 900 ArenaOversell F13 page ma-t9-s20)"
sleep 10
OID=$(create_order "chaos-t9s20-$TS-1"); echo "[create] $OID（补插超额 INVENTORY 行）"
echo "[pay] $(pay_order "$OID" "chaos-t9s20-$TS-1")"
sleep 50
echo "[gauge] $(gauge oa_inventory_negative)"
echo "[alerts]"; alerts ArenaOversell
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's20_inv_rows(期望2=正常1+超额1)='||count(*) from arena.oa_resource_ledger l join arena.oa_trade_order t on t.id=l.order_id where t.correlation_id like 'chaos-t9s20-$TS-%' and l.resource_type='INVENTORY' and l.direction='DEDUCT'"
echo "[off] $(chaos_off F13 "$SCEN")"
wait_closed "$SCEN" 8 oa_inventory_negative
echo "[alerts_final]"; alerts ArenaOversell
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's20_residual_inv_rows(期望1)='||count(*) from arena.oa_resource_ledger l join arena.oa_trade_order t on t.id=l.order_id where t.correlation_id like 'chaos-t9s20-$TS-%' and l.resource_type='INVENTORY' and l.direction='DEDUCT'"
audit_of "$SCEN"
