#!/bin/sh
# p10 受控活体实验：真 API 激活 F1 会话 → 观察 ACTIVE 期窗口内清偿 → 正门关闭
set -e
cd /opt/build/pr/deploy/alert
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
D1=$(sha256sum /etc/hostname | cut -d' ' -f1)
D2=$(sha256sum /etc/os-release | cut -d' ' -f1)
D3=$(date | sha256sum | cut -d' ' -f1)

echo '=== [1] 激活 F1 会话（chaos-manual-p10-drain）==='
docker exec -i deploy-postgres-1 sh -c "wget -qO- --header='X-Admin-Token: $TOKEN' --header='Content-Type: application/json' --post-data='{\"scenarioId\":\"chaos-manual-p10-drain\",\"target\":\"chaos-manual-p10-drain\",\"ttlSeconds\":300,\"operator\":\"p10-manual\",\"configDigest\":\"$D1\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"manual-p10\",\"payloadDigest\":\"$D2\",\"applicableScope\":\"arena\"},\"alertLabels\":{\"severity\":\"page\",\"service\":\"order-arena\",\"job\":\"order-arena\",\"instance\":\"order-arena:8080\",\"alertname\":\"ArenaDuplicateOrders\",\"fault_type\":\"F1\"},\"ruleDigest\":\"$D3\"}' http://arena-chaos-admin:8080/chaos/F1/on"
echo ''
echo '=== [2] 等 12s（2-3 个扫描循环）==='
sleep 12
echo '--- order-arena 清偿日志:'
docker logs --since 2m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '窗口内清偿|恢复收口' | tail -4
echo '--- 单据状态:'
docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -c \
  "select correlation_id, booking_status, discard_reason from arena.oa_trade_order where correlation_id like 'chaos-manual-p10-drain%' order by correlation_id"
