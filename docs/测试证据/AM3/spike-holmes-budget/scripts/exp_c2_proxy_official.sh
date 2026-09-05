#!/usr/bin/env bash
# spike P1 Exp5 (take 2) — LiteLLM proxy budget hard-intercept + SpendLogs audit.
# Proxy = OFFICIAL image litellm/litellm:1.89.0 (same version as the holmes
# client lib), pulled via host registry mirrors. Take 1 (exp_c_proxy.sh) failed:
# pip-installing litellm[proxy] inside the holmes image cannot run prisma
# generate (no prisma node binaries) — see 结论.md.
#   D) holmes -> proxy e2e: per-run virtual key with max_budget
#   A) next calls on exhausted key -> pre-call rejection (hard intercept)
#      proven by instant latency + no new SpendLogs row + proxy budget log
#   B) positive: body metadata.spend_logs_metadata -> SpendLogs.metadata
#      (incl. end-to-end through holmes model_list extra_body)
#   C) negative: custom request header NOT logged in SpendLogs
# Upstream model pricing is pinned via input/output_cost_per_token in the proxy
# config so max_budget triggers deterministically (independent of litellm's
# built-in price map).
# All containers throwaway (memory-limited, removed at end). Keys never printed.
set -u
SPIKE=/tmp/spike
OUT=$SPIKE/out
IMAGE=local/holmesgpt:am1-http
LITELLM_IMAGE=litellm/litellm:1.89.0
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

echo "===== [setup] litellm proxy (official image ${LITELLM_IMAGE}) ====="
cat > "$SPIKE/litellm_config.yaml" <<YAML
model_list:
  - model_name: deepseek-v3
    litellm_params:
      model: openai/${AGENT_MODEL_ID}
      api_base: os.environ/UPSTREAM_BASE
      api_key: os.environ/UPSTREAM_KEY
      input_cost_per_token: 0.0000005
      output_cost_per_token: 0.000001
general_settings:
  master_key: os.environ/MASTER_KEY
litellm_settings:
  drop_params: true
YAML
docker run -d --name spike-litellm --network "$NET" --memory 1024m --cpus 1.0 \
  -p "127.0.0.1:${PROXY_PORT}:4000" \
  -e DATABASE_URL=postgresql://spike:spikepw@spike-pg:5432/litellm \
  -e STORE_MODEL_IN_DB=true \
  -e MASTER_KEY=$MSK \
  -e UPSTREAM_BASE="$AGENT_MODEL_BASE_URL" \
  -e UPSTREAM_KEY="$AGENT_MODEL_API_KEY" \
  -v "$SPIKE/litellm_config.yaml:/app/config.yaml:ro" \
  "$LITELLM_IMAGE" --config /app/config.yaml --port 4000 >/dev/null
PROXY_OK=0
for i in $(seq 1 180); do
  C=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $MSK" \
      "http://127.0.0.1:${PROXY_PORT}/v1/models" 2>/dev/null)
  if [ "$C" = "200" ]; then echo "proxy ready (${i}s)"; PROXY_OK=1; break; fi
  sleep 1
done
if [ "$PROXY_OK" != "1" ]; then
  echo "PROXY FAILED TO START; tail of logs:"
  docker logs spike-litellm 2>&1 | tail -25 | sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g'
  docker logs spike-litellm 2>&1 | sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g' > "$OUT/exp5_proxy_boot_failure2.log"
  docker rm -f spike-pg spike-litellm >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  echo "EXP_C2_DONE (failed)"
  exit 1
fi
docker logs spike-litellm 2>&1 | grep -i -m3 -e "Application startup complete" -e "Prisma client" | sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g' || true
echo "GET /v1/models with master key -> HTTP $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $MSK" "http://127.0.0.1:${PROXY_PORT}/v1/models")"

gen_key() { # $1=payload-json; only the key LENGTH is echoed, never the value
  curl -s -X POST "http://127.0.0.1:${PROXY_PORT}/key/generate" \
    -H "Authorization: Bearer $MSK" -H 'Content-Type: application/json' \
    -d "$1" | grep -o '"key":"[^"]*"' | cut -d'"' -f4
}

echo "===== [D] holmes -> proxy e2e with per-run budget key (max_budget=0.0001) ====="
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
T0=$(date +%s)
docker run --rm --name spike-holmes-run --network "$NET" --memory 1024m --cpus 1.5 \
  -e MODEL=proxy-capped -e ENABLED_BY_DEFAULT_TOOLSETS=internet -e HOME=/tmp \
  -v "$SPIKE/model_list_proxy.yaml:/etc/holmes/config/model_list.yaml:ro" \
  --entrypoint holmes "$IMAGE" ask --model proxy-capped --max-steps 3 \
  "Reply with exactly this single word: PING" >"$OUT/exp5_holmes_via_proxy.log" 2>&1
HOLMES_EXIT=$?
echo "holmes exit=$HOLMES_EXIT elapsed=$(( $(date +%s) - T0 ))s"
tail -12 "$OUT/exp5_holmes_via_proxy.log" | sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g'

echo "===== [A] direct calls on the now-exhausted key -> pre-call rejection ====="
for n in 1 2 3 4; do
  ST=$(curl -s -o "$OUT/exp5_budget_reject_$n.json" -w '%{http_code} %{time_total}s' \
    -X POST "http://127.0.0.1:${PROXY_PORT}/chat/completions" \
    -H "Authorization: Bearer $VK_HOLMES" -H 'Content-Type: application/json' \
    -d '{"model":"deepseek-v3","messages":[{"role":"user","content":"PING?"}],"max_tokens":16}')
  echo "attempt $n: HTTP $ST" | tee -a "$OUT/exp5_budget_reject_status.txt"
  case "$ST" in 200*) sleep 3;; *) break;; esac
done
echo "--- rejection body (attempt with non-200) ---"
for n in 1 2 3 4; do
  if ! grep -q '"choices"' "$OUT/exp5_budget_reject_$n.json" 2>/dev/null; then
    sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g' "$OUT/exp5_budget_reject_$n.json"; echo; break
  fi
done
echo "--- proxy log: budget/reject lines ---"
docker logs spike-litellm 2>&1 | grep -i -m8 -e budget -e exceeded | sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g' | tee "$OUT/exp5_proxy_budget_lines.log" || true
echo "--- key state after rejection (key column NOT selected) ---"
docker exec spike-pg psql -U spike -d litellm -c \
  'SELECT key_alias, max_budget, spend FROM "LiteLLM_VerificationToken" ORDER BY key_alias;' \
  | tee "$OUT/exp5_key_state.txt"

echo "===== [B/C] positive(body metadata) + negative(header), uncapped key ====="
VK_META=$(gen_key '{"key_alias":"spike-run-meta","models":["deepseek-v3"]}')
echo "vk_meta_len=${#VK_META}"
curl -s -o "$OUT/exp5_meta_call.json" -w "meta call: HTTP %{http_code} in %{time_total}s\n" \
  -X POST "http://127.0.0.1:${PROXY_PORT}/chat/completions" \
  -H "Authorization: Bearer $VK_META" -H 'Content-Type: application/json' \
  -H 'X-Run-Id: run-HEADER-002' \
  -d '{"model":"deepseek-v3","messages":[{"role":"user","content":"PING?"}],"max_tokens":16,"user":"spike-user-002","metadata":{"spend_logs_metadata":{"run_id":"run-BODY-002","attempt_id":"a1"}}}' \
  | tee "$OUT/exp5_meta_call_status.txt"
head -c 200 "$OUT/exp5_meta_call.json" | sed -E 's/sk-[A-Za-z0-9]{6,}/sk-REDACTED/g'; echo
echo "waiting for spend-log flush..."
sleep 16

echo "===== spend log rows ====="
docker exec spike-pg psql -U spike -d litellm -c \
  'SELECT api_key_alias, model, spend, prompt_tokens, completion_tokens, metadata FROM "LiteLLM_SpendLogs" ORDER BY request_id;' \
  | tee "$OUT/exp5_spendlogs.txt"

echo "===== [C] header / body / e2e checks ====="
if grep -q "run-HEADER-002" "$OUT/exp5_spendlogs.txt"; then
  echo "UNEXPECTED: header value found in spend log"
else
  echo "CONFIRMED: header value NOT in spend log (headers are not reconcilable)"
fi
if grep -q "run-BODY-002" "$OUT/exp5_spendlogs.txt"; then
  echo "CONFIRMED: body spend_logs_metadata IS in SpendLogs.metadata (direct call)"
else
  echo "UNEXPECTED: body spend_logs_metadata missing from SpendLogs (direct call)"
fi
if grep -q "run-HOLMES-001" "$OUT/exp5_spendlogs.txt"; then
  echo "CONFIRMED: holmes model_list extra_body spend_logs_metadata reached SpendLogs (end-to-end)"
else
  echo "NOTE: no run-HOLMES-001 in SpendLogs (check rows above)"
fi

echo "===== cleanup ====="
docker rm -f spike-pg spike-litellm spike-holmes-run >/dev/null 2>&1
rm -f "$SPIKE/model_list_proxy.yaml" "$SPIKE/litellm_config.yaml"
docker network rm "$NET" >/dev/null 2>&1
docker network rm spike-net >/dev/null 2>&1
echo "EXP_C2_DONE"
