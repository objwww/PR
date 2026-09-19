#!/bin/bash
# M-d 充值探针 v3：在 litellm 容器内打自身代理（localhost:4000，密钥零回显）
set -u
ENVF=/opt/build/pr/deploy/alert/.env
MK=$(grep -E '^(LITELLM_MASTER_KEY|LITELLM_KEY|AGENT_MODEL_API_KEY)=' "$ENVF" | head -1 | cut -d= -f2- | tr -d '\r')
[ -z "$MK" ] && { echo "PROBE_SKIP no-key-in-env"; exit 0; }

for MODEL in deepseek-v3 glm-5; do
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
' "$MK" "$MODEL" 2>&1
done
