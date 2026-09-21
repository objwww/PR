#!/bin/sh
# E2E 步骤2b：白名单 alertname 注入（canary WHITELISTED → 铸 NATIVE run）
# 材料独特化（annotations 唯一）→ invHash 变化 → FIRING 无活跃 run → RERUN
set -eu
TS=$(date -u +%Y%m%dT%H%M%SZ)
FP="e2e-run-$TS"
START=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
PAY=/tmp/e2e-payload-$TS.json
echo "fingerprint=$FP"

cat > "$PAY" <<EOF
{
  "version": "4",
  "groupKey": "CheckoutRpcClientErrorRateHigh:checkout:critical::$FP",
  "receiver": "zhongtai",
  "status": "firing",
  "alerts": [{
    "status": "firing",
    "startsAt": "$START",
    "fingerprint": "$FP",
    "generatorURL": "http://prometheus:9090/graph?g0.expr=e2e",
    "labels": {
      "alertname": "CheckoutRpcClientErrorRateHigh",
      "service": "checkout",
      "severity": "critical",
      "instance": "checkout-7f9c6d8b4-x2demo",
      "env": "prod",
      "team": "trade"
    },
    "annotations": {
      "summary": "E2E 消融取证 #$TS：checkout PlaceOrder 错误率 10 分钟内升至 38%",
      "description": "rpc_server_call 5xx 占比突增至 38%，下游 payments 延迟同步抬升，需 RCA 定位根因。取证批次 $FP。"
    }
  }],
  "commonLabels": {
    "alertname": "CheckoutRpcClientErrorRateHigh",
    "service": "checkout",
    "severity": "critical"
  },
  "commonAnnotations": {
    "summary": "E2E 消融取证 #$TS：checkout PlaceOrder 错误率 10 分钟内升至 38%"
  }
}
EOF

BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $ALERTMANAGER_WEBHOOK_BEARER_TOKEN')
HTTP=$(curl -s -o /tmp/e2e-resp.txt -w '%{http_code}' -X POST http://127.0.0.1:8080/webhooks/alertmanager \
  -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' \
  --data-binary @"$PAY")
echo "webhook_http=$HTTP"
head -c 200 /tmp/e2e-resp.txt; echo
rm -f "$PAY" /tmp/e2e-resp.txt
echo "$FP" > /tmp/e2e-last-fp.txt
exit 0
