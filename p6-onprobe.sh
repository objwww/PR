#!/bin/sh
cd /opt/build/pr/deploy
TOK=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
D1=$(printf 'a%.0s' $(seq 1 64))
docker run --rm --network eval-mgmt curlimages/curl:latest -s -X POST "http://arena-chaos-admin:8080/chaos/F1/on" \
  -H "X-Admin-Token: $TOK" -H 'Content-Type: application/json' --max-time 10 \
  -d "{\"scenarioId\":\"chaos-eval-dbgprobe-s3-r1\",\"target\":\"order-arena chaos- 前缀评测流量的幂等收口路径\",\"ttlSeconds\":600,\"operator\":\"dbg-probe\",\"configDigest\":\"$D1\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"probe\",\"payloadDigest\":\"$D1\",\"applicableScope\":\"eval\"},\"alertLabels\":{\"alertname\":\"ArenaDuplicateOrders\",\"service\":\"order-arena\"},\"ruleDigest\":\"$D1\"}" 2>/dev/null | head -c 300
echo ''
