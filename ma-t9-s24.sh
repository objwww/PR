#!/bin/bash
source /tmp/ma-drill-lib.sh
TS=$(date +%H%M%S); SCEN="ma-t9-s24-$TS"
ENVF=/opt/build/pr/deploy/alert/.env
BAK=/tmp/env-backup-s24-$(date +%s)
echo "======== S24 断流（F16）——需临时停流量发生器（.env 备份纪律）========"
cp "$ENVF" "$BAK"; echo "[env 备份] $BAK"
if grep -q '^TRAFFIC_ENABLED=' "$ENVF"; then
  sed -i 's/^TRAFFIC_ENABLED=.*/TRAFFIC_ENABLED=false/' "$ENVF"
else
  echo 'TRAFFIC_ENABLED=false' >> "$ENVF"
fi
cd /opt/build/pr/deploy/alert && docker compose up -d order-arena 2>&1 | tail -1
sleep 25
echo "[health] $(docker exec alert-order-arena-1 wget -qO- http://127.0.0.1:8080/healthz)"
echo "[on] $(chaos_on F16 "$SCEN" 1800 ArenaOrderZeroFlow F16 page ma-t9-s24)"
sleep 10
echo "[chaos 突发 25 请求：全部被入口静默吞掉（accepted 但零落单）]"
for i in $(seq 1 25); do curl -s -o /dev/null -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
  -d "{\"intentId\":\"chaos-t9s24-$TS-$i\",\"correlationId\":\"chaos-t9s24-$TS-$i\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}" & done; wait
echo "[SQL: 断流期间 chaos 落单数（期望0）] $(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select count(*) from arena.oa_trade_order where correlation_id like 'chaos-t9s24-$TS-%'")"
echo "[等 rate[5m]==0 + for 5m（约 10-11 分钟），轮询 15 分钟]"
FIRED=0
for n in $(seq 1 30); do
  sleep 30
  A=$(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaOrderZeroFlow%22%7D" | grep -c firing)
  echo "poll[$n] firing_hits=$A"
  [ "$A" -ge 1 ] && FIRED=1 && break
done
echo "FIRED=$FIRED"
echo "[off] $(chaos_off F16 "$SCEN")"
echo "[恢复流量：chaos 突发应重新落单]"
for i in $(seq 1 5); do create_order "chaos-t9s24-$TS-after-$i" >/dev/null & done; wait
sleep 45
echo "[SQL: off 后落单数（期望5）] $(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select count(*) from arena.oa_trade_order where correlation_id like 'chaos-t9s24-$TS-after-%'")"
echo "[gauge] $(gauge '(sum(rate(oa_orders_total%5B5m%5D)))')"
echo "[alerts_final]"; alerts ArenaOrderZeroFlow
echo "[恢复 .env 与流量发生器]"
cp "$BAK" "$ENVF" && rm -f "$BAK"
cd /opt/build/pr/deploy/alert && docker compose up -d order-arena 2>&1 | tail -1
sleep 25
echo "[health] $(docker exec alert-order-arena-1 wget -qO- http://127.0.0.1:8080/healthz)"
audit_of "$SCEN"
