#!/usr/bin/env bash
# micro exp: which litellm 1.89.0 client kwargs reach the wire body?
# calls litellm.completion DIRECTLY (no holmes) against the echo server.
set -u
SPIKE=/tmp/spike
NET=spike-net
docker network create "$NET" >/dev/null 2>&1 || true
docker rm -f spike-echo2 >/dev/null 2>&1 || true
docker run -d --name spike-echo2 --network "$NET" --memory 64m \
  -e FAKE_MODE=plain -e ECHO_PORT=8000 \
  -v "$SPIKE/echo_server.py:/srv/echo_server.py:ro" \
  python:3.12-alpine python /srv/echo_server.py >/dev/null
sleep 2

docker run --rm --network "$NET" --memory 512m \
  -v "$SPIKE/micro_client.py:/srv/micro_client.py:ro" \
  --entrypoint python local/holmesgpt:am1-http /srv/micro_client.py 2>&1 \
  | grep -E "K[0-9]|MICRO" || true

echo "===== echo-received bodies ====="
docker logs spike-echo2 2>&1 | grep ECHO_REQUEST | sed 's/^ECHO_REQUEST //' > /tmp/spike/out/micro_reqs.jsonl
python3 - <<'PYEOF' 2>/dev/null || cat /tmp/spike/out/micro_reqs.jsonl | head -4
import json
for line in open('/tmp/spike/out/micro_reqs.jsonl'):
    d = json.loads(line)
    print(d['echo_req_no'], 'body keys:', sorted(d['body'].keys()))
    for k in ('user', 'metadata', 'litellm_metadata', 'extra_body'):
        if k in d['body']:
            print('   ', k, '=', json.dumps(d['body'][k])[:120])
PYEOF
echo "===== echo-received headers of interest (K3) ====="
grep -o '"X-Run-Id": "[^"]*"' /tmp/spike/out/micro_reqs.jsonl || echo "no X-Run-Id header found"

docker rm -f spike-echo2 >/dev/null 2>&1
echo "MICRO_DONE"
