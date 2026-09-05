#!/usr/bin/env bash
# spike P1 Exp1/2/3 — echo-endpoint experiments on 195 (all containers are
# throwaways with memory limits, removed at the end).
#   Exp1: which HTTP headers / body fields holmes -> endpoint (EXTRA_HEADERS,
#         model_list extras user/metadata/litellm_metadata)
#   Exp3: model_list max_tokens=64 vs default (per-call output cap)
#   Exp2: --max-steps 2 vs 4 -> actual LLM call counts (call-count hard cap)
set -u
SPIKE=/tmp/spike
OUT=$SPIKE/out
IMAGE=local/holmesgpt:am1-http
NET=spike-net
mkdir -p "$OUT"

EXTRA_HEADERS='{"X-Run-Id":"run-20260905-spike","X-Attempt-Id":"attempt-1","x-litellm-metadata":"{\"run_id\":\"run-20260905-spike\",\"attempt\":1}"}'

net_up() { docker network create "$NET" >/dev/null 2>&1 || true; }

echo_up() { # $1 = FAKE_MODE
  docker rm -f spike-echo >/dev/null 2>&1 || true
  docker run -d --name spike-echo --network "$NET" --memory 64m --cpus 0.5 \
    -e FAKE_MODE="$1" -e ECHO_PORT=8000 \
    -v "$SPIKE/echo_server.py:/srv/echo_server.py:ro" \
    python:3.12-alpine python /srv/echo_server.py >/dev/null
  sleep 2
  docker logs spike-echo 2>&1 | head -1
}

run_holmes() { # $1=model $2=max-steps $3=label ; prints holmes output
  docker run --rm --network "$NET" --memory 1024m --cpus 1.5 \
    -e MODEL="$1" -e ENABLED_BY_DEFAULT_TOOLSETS=internet -e HOME=/tmp \
    -e EXTRA_HEADERS="$EXTRA_HEADERS" \
    -v "$SPIKE/model_list.yaml:/etc/holmes/config/model_list.yaml:ro" \
    --entrypoint holmes "$IMAGE" ask --model "$1" --max-steps "$2" "what is breaking?" 2>&1
}

net_up
echo "===== holmes CLI version ====="
docker run --rm --memory 256m -e HOME=/tmp --entrypoint holmes "$IMAGE" --version 2>&1 | head -3

echo "===== [Exp1] phase 1: echo(plain) + spike-plain, default max_steps ====="
echo_up plain
run_holmes spike-plain 10 >"$OUT/exp1_holmes_plain.out" 2>&1
echo "holmes exit=$? ; tail:"
tail -5 "$OUT/exp1_holmes_plain.out"
docker logs spike-echo >"$OUT/exp1_echo_req_plain.log" 2>&1
grep -c ECHO_REQUEST "$OUT/exp1_echo_req_plain.log"

echo "===== [Exp1] phase 1b: spike-probe (user/metadata/litellm_metadata in model_list) ====="
run_holmes spike-probe 10 >"$OUT/exp1_holmes_probe.out" 2>&1
echo "holmes exit=$?"
docker logs spike-echo >"$OUT/exp1_echo_req_probe.log" 2>&1

echo "===== [Exp3] spike-capped (max_tokens: 64) ====="
run_holmes spike-capped 10 >"$OUT/exp3_holmes_capped.out" 2>&1
echo "holmes exit=$?"
docker logs spike-echo >"$OUT/exp3_echo_req_capped.log" 2>&1

echo "===== [Exp2] echo(toolloop): --max-steps 2 then 4 ====="
echo_up toolloop
run_holmes spike-plain 2 >"$OUT/exp2_holmes_steps2.out" 2>&1
echo "steps=2 exit=$?"
docker logs spike-echo >"$OUT/exp2_echo_req_steps2.log" 2>&1
run_holmes spike-plain 4 >"$OUT/exp2_holmes_steps4.out" 2>&1
echo "steps=4 exit=$?"
docker logs spike-echo >"$OUT/exp2_echo_req_steps4.log" 2>&1

echo "===== [Exp2] echo(toolloop): baseline max-steps 1 ====="
run_holmes spike-plain 1 >"$OUT/exp2_holmes_steps1.out" 2>&1
echo "steps=1 exit=$?"
docker logs spike-echo >"$OUT/exp2_echo_req_steps1.log" 2>&1

echo "===== counts (ECHO_REQUEST per log) ====="
for f in "$OUT"/exp*_echo_req_*.log; do
  echo "$f : $(grep -c ECHO_REQUEST "$f")"
done

echo "===== cleanup ====="
docker rm -f spike-echo >/dev/null 2>&1
docker network rm "$NET" >/dev/null 2>&1
docker ps -a --format '{{.Names}}' | grep spike || echo "no spike containers left"
echo "EXP_A_DONE"
