#!/usr/bin/env bash
# spike P1 Exp4 — real endpoint, 3x identical fixed prompt, observe per-call
# token usage reported by holmes (LOG_LLM_USAGE_RESPONSE/TRACE_TOKEN_USAGE).
# Key sourced at runtime from /opt/build/pr/.env; never printed.
set -u
SPIKE=/tmp/spike
OUT=$SPIKE/out
IMAGE=local/holmesgpt:am1-http
NET=spike-net
mkdir -p "$OUT"
set -a; . /opt/build/pr/.env; set +a

docker network create "$NET" >/dev/null 2>&1 || true

cat > "$SPIKE/model_list_real.yaml" <<YAML
real-capped:
  model: openai/${AGENT_MODEL_ID}
  api_key: ${AGENT_MODEL_API_KEY}
  base_url: ${AGENT_MODEL_BASE_URL}
  max_tokens: 128
YAML
chmod 600 "$SPIKE/model_list_real.yaml"

FIXED_PROMPT='Reply with exactly this single word: PING'
for i in 1 2 3; do
  echo "===== real run #$i ====="
  docker run --rm --network "$NET" --memory 1024m --cpus 1.5 \
    -e MODEL=real-capped -e ENABLED_BY_DEFAULT_TOOLSETS=internet -e HOME=/tmp \
    -e LOG_LLM_USAGE_RESPONSE=true -e TRACE_TOKEN_USAGE=true \
    -v "$SPIKE/model_list_real.yaml:/etc/holmes/config/model_list.yaml:ro" \
    --entrypoint holmes "$IMAGE" ask --model real-capped --max-steps 1 "$FIXED_PROMPT" \
    >"$OUT/exp4_holmes_real_$i.log" 2>&1
  echo "exit=$?; usage lines:"
  grep -i -A4 "usage" "$OUT/exp4_holmes_real_$i.log" | head -20
done

echo "===== summary extraction ====="
grep -h -E "input=|LLM usage|prompt_tokens|completion_tokens|total_tokens|cost" \
  "$OUT"/exp4_holmes_real_*.log | head -30

rm -f "$SPIKE/model_list_real.yaml"
docker network rm "$NET" >/dev/null 2>&1
echo "EXP_B_DONE"
