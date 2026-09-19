#!/bin/bash
# M-d 充值探针 v4：用 control-app 实际生效的 AGENT_MODEL_API_KEY（容器 env 直取，零回显），
# 在 litellm 容器内打代理——key/模型/回显内容均不落日志
set -u
CA=$(docker ps --format '{{.Names}}' | grep -E 'control-app' | head -1)
[ -z "$CA" ] && { echo "PROBE_SKIP no-control-app"; exit 0; }
KEY=$(docker exec "$CA" sh -c 'env | grep -E "^AGENT_MODEL_API_KEY=" | head -1 | cut -d= -f2-')
MODEL=$(docker exec "$CA" sh -c 'env | grep -E "^AGENT_MODEL=" | head -1 | cut -d= -f2-')
[ -z "$KEY" ] && { echo "PROBE_SKIP no-agent-key-in-control-app"; exit 0; }
echo "agent_model=$MODEL key_present=yes (不回显)"

docker exec -i litellm-am3 python3 -c '
import sys, json, urllib.request
key = sys.argv[1]; model = sys.argv[2]
body = json.dumps({"model": model, "messages": [{"role": "user", "content": "ping"}],
                   "max_tokens": 4, "temperature": 0}).encode()
req = urllib.request.Request("http://localhost:4000/chat/completions", data=body,
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
try:
    r = urllib.request.urlopen(req, timeout=40)
    txt = r.read().decode()
    print(model, "PROBE_OK", r.status, txt[:90])
except urllib.error.HTTPError as e:
    print(model, "PROBE_HTTP", e.code, e.read().decode()[:200])
except Exception as e:
    print(model, "PROBE_ERR", type(e).__name__, str(e)[:120])
' "$KEY" "${MODEL:-deepseek-v3}" 2>&1
