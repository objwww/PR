#!/bin/sh
cd /opt/build/pr
# 在 docker run 之前把 SPRING_JSON 打进日志（运行时真相）
sed -i 's|^nohup docker run|echo SPRING_AT_RUNTIME\nnohup docker run|' /tmp/p6w.sh
sed -i '/^echo SPRING_AT_RUNTIME/a echo "$SPRING_JSON" | tee /tmp/p6spring.txt | head -c 120' /tmp/p6w.sh
docker rm -f eval-worker-p6real >/dev/null 2>&1 || true
sh /tmp/p6w.sh || true
echo '===runtime json==='
head -c 300 /tmp/p6spring.txt
echo ''
wc -c < /tmp/p6spring.txt
docker exec -i litellm-am3 python -c "import json,sys; json.load(sys.stdin); print('RUNTIME-JSON-VALID')" < /tmp/p6spring.txt 2>&1 | tail -1
