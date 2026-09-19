#!/bin/bash
# M-d 充值探针 v2：自动发现 litellm 容器与其网络，从同网容器发起最小补全（密钥零回显）
set -u
ENVF=/opt/build/pr/deploy/alert/.env
MK=$(grep -E '^(LITELLM_MASTER_KEY|LITELLM_KEY|AGENT_MODEL_API_KEY)=' "$ENVF" | head -1 | cut -d= -f2- | tr -d '\r')
[ -z "$MK" ] && { echo "PROBE_SKIP no-key-in-env"; exit 0; }

LC=$(docker ps --format '{{.Names}}' | grep -i litellm | head -1)
echo "litellm_container=$LC"
[ -z "$LC" ] && { echo "PROBE_SKIP no-litellm-container"; exit 0; }

LNET=$(docker inspect "$LC" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}')
echo "litellm_nets=$LNET"
NET=$(echo $LNET | awk '{print $1}')
PORT=$(docker port "$LC" 2>/dev/null | head -1)
echo "host_port_map=$PORT"

# 同网探针容器：借用 flagd-admin-am3，若不在同网则用 litellm 容器自身的 python
SRC=$(docker ps --format '{{.Names}}' | grep -m1 flagd-admin)
echo "probe_src=$SRC net_of_src=$(docker inspect "$SRC" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null)"

for MODEL in deepseek-v3 glm-5; do
  docker exec "$SRC" python3 -c '
import sys, json, socket, urllib.request
key = sys.argv[1]; model = sys.argv[2]; host = sys.argv[3]
try:
    ip = socket.gethostbyname(host)
except Exception as e:
    print(model, "DNS_FAIL", host, str(e)[:60]); raise SystemExit
body = json.dumps({"model": model, "messages": [{"role": "user", "content": "ping"}],
                   "max_tokens": 4, "temperature": 0}).encode()
req = urllib.request.Request("http://" + host + ":4000/chat/completions", data=body,
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
try:
    r = urllib.request.urlopen(req, timeout=30)
    print(model, "PROBE_OK", r.status, r.read().decode()[:90])
except urllib.error.HTTPError as e:
    print(model, "PROBE_HTTP", e.code, e.read().decode()[:150])
except Exception as e:
    print(model, "PROBE_ERR", type(e).__name__, str(e)[:100])
' "$MK" "$MODEL" litellm 2>&1
done
