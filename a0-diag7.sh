#!/bin/sh
# checkout 指标序列清单（近 1h 有数据的）
docker exec prometheus-am0 sh -c "wget -qO- --timeout=8 'http://localhost:9090/api/v1/query?query=count%20by%20(__name__)%20(%7Bservice%3D%22checkout%22%7D)' 2>/dev/null" | head -c 1500
echo ''
exit 0
