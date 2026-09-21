#!/bin/sh
# prometheus 标签修复：补丁 + 重启 + 恢复验证
python3 /opt/build/prom-patch.py || exit 1
docker restart prometheus-am0
sleep 25
echo '=== checkout 序列计数（修复后） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=count(%7Bservice%3D%22checkout%22%7D)' 2>/dev/null" | head -c 200
echo ''
echo '=== 全栈 service 分布（top5） ==='
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=topk(5,count%20by(service)(%7Bservice%3D~%22.%2B%22%7D))' 2>/dev/null" | head -c 600
echo ''
exit 0
