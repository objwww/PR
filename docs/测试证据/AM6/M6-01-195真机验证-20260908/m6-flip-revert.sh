set -e
cd /opt/build/pr/deploy
BAK=.env.bak-pre-m6flip-$(date +%Y%m%d%H%M%S)
cp -a .env $BAK
echo BACKUP=$BAK
printf '\n# AM6 M6-01 native capability flip (195 real-machine evidence 2026-09-08)\nAPP_ALERT_NATIVE_METRICS_EXPR=oa_duplicate_orders_current{job="order-arena"}\nAPP_ALERT_NATIVE_TOOL_REGISTRY_DIGEST=am6-native:prometheus.query,logs.query,change.query\n' >> .env
docker compose up -d control-app >/dev/null
sleep 18
. ./.env
P=${CONTROL_PORT:-8080}
CODE=$(curl -s -o /tmp/m6-flip.json -w '%{http_code}' -H "Authorization: Bearer $APP_RELEASE_API_BEARER" http://127.0.0.1:$P/api/canary/status)
echo FLIP_CODE=$CODE
cat /tmp/m6-flip.json; echo

echo '--- revert to fail-closed default'
cp -a $BAK .env
docker compose up -d control-app >/dev/null
sleep 18
CODE2=$(curl -s -o /tmp/m6-rev.json -w '%{http_code}' -H "Authorization: Bearer $APP_RELEASE_API_BEARER" http://127.0.0.1:$P/api/canary/status)
echo REVERT_CODE=$CODE2
cat /tmp/m6-rev.json; echo
rm -f /tmp/m6-flip.json /tmp/m6-rev.json
echo FLIP-REVERT-DONE
