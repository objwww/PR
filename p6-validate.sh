#!/bin/sh
cd /opt/build/pr
sed 's/--rm //; s/eval-worker-p6real/eval-worker-p6dbg/' /tmp/p6w.sh > /tmp/p6wdbg.sh
sh /tmp/p6wdbg.sh >/dev/null 2>&1 || true
sleep 3
docker inspect eval-worker-p6dbg --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^SPRING_APPLICATION_JSON=' | sed 's/^SPRING_APPLICATION_JSON=//' > /tmp/p6env.json
wc -c < /tmp/p6env.json
docker cp /tmp/p6env.json litellm-am3:/tmp/p6env.json >/dev/null 2>&1
docker exec litellm-am3 python -c "import json; json.load(open('/tmp/p6env.json')); print('JSON-VALID')" 2>&1 | tail -3
docker rm -f eval-worker-p6dbg >/dev/null 2>&1
