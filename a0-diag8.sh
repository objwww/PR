#!/bin/sh
# checkout 指标端点直探 + 最近有数据窗
echo '=== checkout:9465/metrics 可达性 ==='
docker exec otel-collector sh -c 'wget -qO- --timeout=5 http://checkout:9465/metrics 2>/dev/null | head -5 || echo CHECKOUT-METRICS-UNREACHABLE'
echo '=== 最近 30m 内 service=checkout 任意序列（last_over_time） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=count(last_over_time(%7Bservice%3D%22checkout%22%7D%5B30m%5D))' 2>/dev/null" | head -c 200
echo ''
echo '=== 对照：service=ad 近 5m ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=count(%7Bservice%3D%22ad%22%7D)' 2>/dev/null" | head -c 200
echo ''
echo '=== 全部 demo 序列近 5m 计数（按 service top） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=topk(8,count%20by(service)(%7Bservice%3D~%22.%2B%22%7D))' 2>/dev/null" | head -c 800
echo ''
exit 0
