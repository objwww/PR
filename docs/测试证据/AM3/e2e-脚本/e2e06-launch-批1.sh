#!/bin/sh
# E2E-M3-06 LiteLLM 故障链 · 启动（停 proxy + S3 批 am3-e2e06-0905）
# 断言面（§12 E2E-M3-06）：proxy 不可达/usage 缺失 → 不得错误 MATCHED；
#   降级三态 PARTIAL/UNMATCHED/BEST_EFFORT 落台账；批件终态不受对账失败阻断。
set -eu
echo "== echo 基线 =="
docker run --rm --network alert-ab curlimages/curl:8.8.0 -s "http://echo-receiver:8080/__admin/requests" \
  > /tmp/m330-e2e06-echo-before.json 2>/dev/null || echo '[]' > /tmp/m330-e2e06-echo-before.json
echo "== 停 litellm（故障注入面） =="
docker stop litellm-am3
sleep 3
docker ps --filter name=litellm-am3 --format '{{.Names}} {{.Status}}'
echo "== 启动 S3 批 =="
docker rm -f eval-runner-am3-e2e06-0905 2>/dev/null || true
cd /opt/build/pr
nohup sh /tmp/cc-m330-evalrun.sh deploy/alert/eval/eval-scenarios-s3.yml am3-e2e06-0905 \
  > /tmp/m330-am3-e2e06-0905-run.log 2>&1 &
sleep 5
docker ps --filter name=eval-runner-am3-e2e06-0905 --format '{{.Names}} {{.Status}}'
exit 0
