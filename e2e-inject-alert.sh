#!/bin/sh
# E2E 步骤2（v4）：宿主机生成 AM 信封 JSON 文件后 curl；bearer 运行时提取不回显
set -eu
TS=$(date -u +%Y%m%dT%H%M%SZ)
FP="e2e-full-$TS"
START=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
PAY=/tmp/e2e-payload-$TS.json
echo "fingerprint=$FP"

cat > "$PAY" <<EOF
{
  "version": "4",
  "groupKey": "E2EAblationFullFlow:checkout:critical::$FP",
  "receiver": "zhongtai",
  "status": "firing",
  "alerts": [{
    "status": "firing",
    "startsAt": "$START",
    "fingerprint": "$FP",
    "generatorURL": "http://prometheus:9090/graph?g0.expr=e2e",
    "labels": {
      "alertname": "E2EAblationFullFlow",
      "service": "checkout",
      "severity": "critical",
      "instance": "checkout-7f9c6d8b4-x2demo",
      "env": "prod",
      "team": "trade"
    },
    "annotations": {
      "summary": "E2E 演示：checkout PlaceOrder 错误率 10 分钟内升至 38%",
      "description": "rpc_server_call 5xx 占比突增，下游 payments 延迟同步抬升，需 RCA 定位根因。"
    }
  }],
  "commonLabels": {
    "alertname": "E2EAblationFullFlow",
    "service": "checkout",
    "severity": "critical"
  },
  "commonAnnotations": {
    "summary": "E2E 演示：checkout PlaceOrder 错误率 10 分钟内升至 38%"
  }
}
EOF

BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $ALERTMANAGER_WEBHOOK_BEARER_TOKEN')
HTTP=$(curl -s -o /tmp/e2e-resp.txt -w '%{http_code}' -X POST http://127.0.0.1:8080/webhooks/alertmanager \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  --data-binary @"$PAY")
echo "webhook_http=$HTTP"
head -c 300 /tmp/e2e-resp.txt; echo
rm -f "$PAY" /tmp/e2e-resp.txt
echo "$FP" > /tmp/e2e-last-fp.txt

sleep 10
echo '=== 新 incident ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id || ' | key=' || incident_key || ' | ' || status || ' | run=' || coalesce(current_rca_run_id::text,'-') from incident where incident_key like '%${FP}%' order by created_at desc limit 3"
echo '=== 新 run ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.id || ' | ' || r.state from rca_run r join incident i on r.incident_id=i.id where i.incident_key like '%${FP}%'"
exit 0
