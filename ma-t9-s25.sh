#!/bin/bash
source /tmp/ma-drill-lib.sh
TS=$(date +%H%M%S); SCEN="ma-t9-s25-$TS"
echo "======== S25 履约超时积压（F17）========"
echo "[on] $(chaos_on F17 "$SCEN" 900 ArenaFulfillmentSlaBreach F17 ticket ma-t9-s25)"
sleep 10
echo "[create 16 单：booking ENABLED，履约停 CONFIRMING]"
for i in $(seq 1 16); do create_order "chaos-t9s25-$TS-$i" >/dev/null & done; wait
echo "[wait 75s（超龄 60s + 探测窗）]"
sleep 75
echo "[gauge(期望>15)] $(gauge oa_fulfillment_overdue_current)"
echo "[alerts]"; alerts ArenaFulfillmentSlaBreach
echo "[off] $(chaos_off F17 "$SCEN")"
wait_closed "$SCEN" 10 oa_fulfillment_overdue_current
echo "[alerts_final]"; alerts ArenaFulfillmentSlaBreach
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's25_residual_confirming(期望0)='||count(*) from arena.oa_fulfillment_order f join arena.oa_trade_order t on t.id=f.trade_order_id where t.correlation_id like 'chaos-t9s25-$TS-%' and f.state='CONFIRMING'"
audit_of "$SCEN"
