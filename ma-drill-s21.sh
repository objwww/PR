#!/bin/bash
source /tmp/ma-drill-lib.sh
TS=$(date +%H%M%S)
SCEN="ma-t9-s20-$TS"

echo "======== S20 库存超卖（F13）========"
echo "[S20 activate] $(call_chaos F13 on "$SCEN" 900 ArenaOversell F13 page ma-t9-s20)"
sleep 10
OID=$(create_order "chaos-t9s20-$TS-1")
echo "[S20 create] orderId=$OID（F13 补插超额 INVENTORY 行）"
P=$(pay_order "$OID" "chaos-t9s20-$TS-1"); echo "[S20 pay] $P"
sleep 50
echo "[S20 gauge] $(gauge oa_inventory_negative)"
echo "[S20 alerts] $(alerts ArenaOversell)"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's20_inv_rows='||count(*) from arena.oa_resource_ledger l join arena.oa_trade_order t on t.id=l.order_id where t.correlation_id like 'chaos-t9s20-$TS-%' and l.resource_type='INVENTORY' and l.direction='DEDUCT'"
echo "[S20 off] $(call_chaos F13 off "$SCEN" 0 0 0 0 0 2>/dev/null)"
echo "{\"scenarioId\":\"$SCEN\",\"expectedGeneration\":0}" | docker exec -i flagd-admin-am3 python3 -c '
import sys, urllib.request
token = sys.argv[1]; body = sys.stdin.buffer.read()
req = urllib.request.Request("http://arena-chaos-admin:8080/chaos/F13/off", data=body,
    headers={"Content-Type": "application/json", "X-Admin-Token": token})
try:
    r = urllib.request.urlopen(req, timeout=10); print(r.status, r.read().decode())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
' "$TOKEN" 2>&1
wait_closed "$SCEN" 8 oa_inventory_negative
echo "[S20 alerts_final] $(alerts ArenaOversell)"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's20_residual_inv_rows='||count(*) from arena.oa_resource_ledger l join arena.oa_trade_order t on t.id=l.order_id where t.correlation_id like 'chaos-t9s20-$TS-%' and l.resource_type='INVENTORY' and l.direction='DEDUCT'"
audit_of "$SCEN"
