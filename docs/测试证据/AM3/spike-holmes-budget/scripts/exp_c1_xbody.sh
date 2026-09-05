#!/usr/bin/env bash
# exp_c1: does holmes model_list entry extra_body reach the wire body?
set -u
SPIKE=/tmp/spike
NET=spike-net
docker network create "$NET" >/dev/null 2>&1 || true
docker rm -f spike-echo3 >/dev/null 2>&1 || true
docker run -d --name spike-echo3 --network "$NET" --memory 64m \
  -e FAKE_MODE=plain -e ECHO_PORT=8000 \
  -v "$SPIKE/echo_server.py:/srv/echo_server.py:ro" \
  python:3.12-alpine python /srv/echo_server.py >/dev/null
sleep 2

cat > "$SPIKE/model_list_xbody.yaml" <<'YAML'
xbody-model:
  model: openai/deepseek-v3
  api_key: dummy-key-for-echo
  base_url: http://spike-echo3:8000/v1
  max_tokens: 32
  extra_body:
    litellm_metadata:
      spend_logs_metadata:
        run_id: run-FROM-HOLMES-XBODY
        attempt_id: a1
YAML

docker run --rm --network "$NET" --memory 1024m --cpus 1.5 \
  -e MODEL=xbody-model -e ENABLED_BY_DEFAULT_TOOLSETS=internet -e HOME=/tmp \
  -v "$SPIKE/model_list_xbody.yaml:/etc/holmes/config/model_list.yaml:ro" \
  --entrypoint holmes local/holmesgpt:am1-http ask --model xbody-model --max-steps 1 \
  "Reply with exactly this single word: PING" >/tmp/spike/out/exp_c1_holmes.out 2>&1
echo "holmes exit=$?"

docker logs spike-echo3 2>&1 | grep ECHO_REQUEST | sed 's/^ECHO_REQUEST //' > /tmp/spike/out/expc1_reqs.jsonl
echo "===== echo-received body keys ====="
python3 - <<'PYEOF' 2>/dev/null || head -c 600 /tmp/spike/out/expc1_reqs.jsonl
import json
for line in open('/tmp/spike/out/expc1_reqs.jsonl'):
    d = json.loads(line)
    b = d['body']
    print(d['echo_req_no'], 'body keys:', sorted(b.keys()))
    if 'litellm_metadata' in b:
        print('   litellm_metadata =', json.dumps(b['litellm_metadata']))
    if 'extra_body' in b:
        print('   extra_body(verbatim!) =', json.dumps(b['extra_body'])[:200])
PYEOF

docker rm -f spike-echo3 >/dev/null 2>&1
echo "EXP_C1_DONE"
