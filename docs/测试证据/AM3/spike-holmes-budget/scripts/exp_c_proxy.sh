#!/usr/bin/env bash
# spike P1 Exp5 — LiteLLM proxy (pip litellm[proxy]==1.89.0, same version as
# the holmes client library) budget hard-intercept + spend-log audit.
#   D) holmes -> proxy e2e: per-run virtual key with max_budget
#   A) budget-exceeded -> pre-call rejection (hard intercept)
#   B) positive: body metadata.spend_logs_metadata -> SpendLogs.metadata
#   C) negative: custom request header NOT logged in SpendLogs
# All containers throwaway (memory-limited, removed at end).
set -u
SPIKE=/tmp/spike
OUT=$SPIKE/out
IMAGE=local/holmesgpt:am1-http
NET=spike-net2
MSK=sk-spike-master-000
PROXY_PORT=14000
mkdir -p "$OUT"
set -a; . /opt/build/pr/.env; set +a

docker network create "$NET" >/dev/null 2>&1 || true
docker rm -f spike-pg spike-litellm spike-holmes-run >/dev/null 2>&1 || true

echo "===== [setup] postgres ====="
docker run -d --name spike-pg --network "$NET" --memory 256m \
  -e POSTGRES_USER=spike -e POSTGRES_PASSWORD=spikepw -e POSTGRES_DB=litellm \
  postgres:16-alpine >/dev/null
for i in $(seq 1 30); do
  if docker exec spike-pg pg_isready -U spike >/dev/null 2>&1; then echo "pg ready (${i}s)"; break; fi
  sleep 1
done

echo "===== [setup] litellm proxy (litellm[proxy]==1.89.0 in $IMAGE) ====="
cat > "$SPIKE/litellm_config.yaml" <<YAML
model_list:
  - model_name: deepseek-v3
    litellm_params:
      model: openai/${AGENT_MODEL_ID}
      api_base: os.environ/UPSTREAM_BASE
      api_key: os.environ/UPSTREAM_KEY
general_settings:
  master_key: os.environ/MASTER_KEY
litellm_settings:
  drop_params: true
YAML
docker run -d --name spike-litellm --network "$NET" --memory 768m --cpus 1.0 \
  -p "127.0.0.1:${PROXY_PORT}:4000" \
  -e DATABASE_URL=postgresql://spike:spikepw@spike-pg:5432/litellm \
  -e STORE_MODEL_IN_DB=true \
  -e MASTER_KEY=$MSK \
  -e UPSTREAM_BASE="$AGENT_MODEL_BASE_URL" \
  -e UPSTREAM_KEY="$AGENT_MODEL_API_KEY" \
  -v "$SPIKE/litellm_config.yaml:/app/config.yaml:ro" \
  --entrypoint sh "$IMAGE" -c \
  "apt-get update >/tmp/apt.log 2>&1 && apt-get install -y libatomic1 >>/tmp/apt.log 2>&1 && pip install --no-cache-dir 'litellm[proxy]==1.89.0' 'prisma' -i https://pypi.tuna.tsinghua.edu.cn/simple >/tmp/pip_install.log 2>&1 && prisma generate >/tmp/prisma.log 2>&1 && exec litellm --config /app/config.yaml --port 4000" >/dev/null
PROXY_OK=0
for i in $(seq 1 420); do
  C=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $MSK" \
      "http://127.0.0.1:${PROXY_PORT}/v1/models" 2>/dev/null)
  if [ "$C" = "200" ]; then echo "proxy ready (${i}s)"; PROXY_OK=1; break; fi
  sleep 1
done
if [ "$PROXY_OK" != "1" ]; then
  echo "PROXY FAILED TO START; tail of logs:"
  docker logs spike-litellm 2>&1 | tail -25
  docker logs spike-litellm 2>&1 > "$OUT/exp5_proxy_boot_failure.log"
  docker rm -f spike-pg spike-litellm >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  echo "EXP_C_DONE (failed)"
  exit 1
fi
docker logs spike-litellm 2>&1 | grep -i -m6 -e "error" -e "Application startup complete" || true

gen_key() { # $1=payload-json
  curl -s -X POST "http://127.0.0.1:${PROXY_PORT}/key/generate" \
    -H "Authorization: Bearer $MSK" -H 'Content-Type: application/json' \
    -d "$1" | grep -o '"key":"[^"]*"' | cut -d'"' -f4
}

echo "===== [D] holmes -> proxy with per-run budget key ====="
VK_HOLMES=$(gen_key '{"key_alias":"spike-run-holmes","max_budget":0.0001,"models":["deepseek-v3"]}')
echo "vk_holmes_len=${#VK_HOLMES}"
cat > "$SPIKE/model_list_proxy.yaml" <<YAML
proxy-capped:
  model: openai/deepseek-v3
  api_key: ${VK_HOLMES}
  base_url: http://spike-litellm:4000/v1
  max_tokens: 32
  extra_body:
    litellm_metadata:
      spend_logs_metadata:
        run_id: run-HOLMES-001
        attempt_id: a1
YAML
docker run --rm --name spike-holmes-run --network "$NET" --memory 1024m --cpus 1.5 \
  -e MODEL=proxy-capped -e ENABLED_BY_DEFAULT_TOOLSETS=internet -e HOME=/tmp \
  -v "$SPIKE/model_list_proxy.yaml:/etc/holmes/config/model_list.yaml:ro" \
  --entrypoint holmes "$IMAGE" ask --model proxy-capped --max-steps 3 \
  "Reply with exactly this single word: PING" >"$OUT/exp5_holmes_via_proxy.log" 2>&1
echo "holmes exit=$?"
tail -8 "$OUT/exp5_holmes_via_proxy.log"

echo "===== [A] next call on exhausted key -> hard reject ====="
curl -s -X POST "http://127.0.0.1:${PROXY_PORT}/chat/completions" \
  -H "Authorization: Bearer $VK_HOLMES" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v3","messages":[{"role":"user","content":"PING?"}],"max_tokens":16}' \
  >"$OUT/exp5_budget_reject.json" 2>&1
cat "$OUT/exp5_budget_reject.json"; echo

echo "===== [B/C] positive(body metadata) + negative(header) ====="
VK_META=$(gen_key '{"key_alias":"spike-run-meta","models":["deepseek-v3"]}')
curl -s -X POST "http://127.0.0.1:${PROXY_PORT}/chat/completions" \
  -H "Authorization: Bearer $VK_META" -H 'Content-Type: application/json' \
  -H 'X-Run-Id: run-HEADER-002' \
  -d '{"model":"deepseek-v3","messages":[{"role":"user","content":"PING?"}],"max_tokens":16,"user":"spike-user-002","metadata":{"spend_logs_metadata":{"run_id":"run-BODY-002","attempt_id":"a1"}}}' \
  >"$OUT/exp5_meta_call.json" 2>&1
head -c 300 "$OUT/exp5_meta_call.json"; echo
sleep 6

echo "===== spend log rows ====="
docker exec spike-pg psql -U spike -d litellm -c \
  'SELECT api_key_alias, model, spend, metadata FROM "LiteLLM_SpendLogs" ORDER BY request_id;' \
  | tee "$OUT/exp5_spendlogs.txt"

echo "===== [C] header negative check ====="
if grep -q "run-HEADER-002" "$OUT/exp5_spendlogs.txt"; then
  echo "UNEXPECTED: header value found in spend log"
else
  echo "CONFIRMED: header value NOT in spend log (headers are not reconcilable)"
fi

echo "===== cleanup ====="
docker rm -f spike-pg spike-litellm spike-holmes-run >/dev/null 2>&1
rm -f "$SPIKE/model_list_proxy.yaml" "$SPIKE/litellm_config.yaml"
docker network rm "$NET" >/dev/null 2>&1
echo "EXP_C_DONE"
