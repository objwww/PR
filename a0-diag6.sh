#!/bin/sh
# up 序列标签形状 + checkout 关键指标
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/query?query=up%3D%3D1' 2>/dev/null" | head -c 1200
echo ''
echo '=== checkout 相关序列（无 label 过滤，按 job/instance 猜） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/label/service/values' 2>/dev/null" | head -c 500
echo ''
docker exec prometheus-am0 sh -c "wget -qO- --timeout=5 'http://localhost:9090/api/v1/label/job/values' 2>/dev/null" | head -c 400
echo ''
exit 0
