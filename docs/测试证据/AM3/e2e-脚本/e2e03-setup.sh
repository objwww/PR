#!/bin/sh
# E2E-M3-03 结构失败链 · 阶段一：注入面搭建 + 批量启动（tag am3-e2e03-0905）
# 注入设计：badmodel stub（OpenAI 形状、内容 = 非 JSON 散文）经 litellm 运行时路由
#   /model/new（MASTER_KEY，STORE_MODEL_IN_DB 面）→ holmes 临时容器 MODEL=badmodel
#   （原容器 stop+rename 保留，恢复 = rename 回）。断言面见 cc-m330-e2e03-assert.sh。
#   MODEL 必须带 openai/ 前缀（同生产 MODEL=openai/deepseek-v3；裸名 litellm SDK 本地
#   解析不出 provider，请求未出网即 BadRequest——M3-30 实测教训）。
# 前提：M3-02 批已结束（holmes/litellm 空闲）；control-app 镜像已含 scorer 修复。
set -eu
cd /opt/build/pr
HOLMES_KEY=$(grep '^HOLMES_API_KEY=' deploy/.env | cut -d= -f2)
RUN_KEY=$(grep '^LITELLM_RUN_KEY=' deploy/alert/.env | cut -d= -f2)
MK=$(grep '^LITELLM_MASTER_KEY=' deploy/alert/.env | cut -d= -f2)

echo "== 0. echo-receiver 基线计数（d07 用） =="
docker run --rm --network alert-ab curlimages/curl:8.8.0 -s "http://echo-receiver:8080/__admin/requests" \
  > /tmp/m330-e2e03-echo-before.json 2>/dev/null || echo '[]' > /tmp/m330-e2e03-echo-before.json
grep -c "am3/testbot" /tmp/m330-e2e03-echo-before.json || echo 0

echo "== 1. badmodel stub 启动（alert-net，端口 8080） =="
docker rm -f badmodel-stub 2>/dev/null || true
docker run -d --name badmodel-stub --network alert-net --entrypoint python \
  -v /tmp/badmodel-stub.py:/stub.py:ro pr-agent/flagd-admin:0.0.1-SNAPSHOT -u /stub.py
# 注：该镜像 ENTRYPOINT=server.py，必须 --entrypoint python 覆盖（否则 stub 不监听）
sleep 3
docker run --rm --network alert-net curlimages/curl:8.8.0 -s -X POST http://badmodel-stub:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -d '{"model":"badmodel","messages":[{"role":"user","content":"ping"}]}' | head -c 300
echo

echo "== 2. litellm 运行时路由 badmodel → stub =="
curl -s -X POST http://127.0.0.1:4100/model/new \
  -H "Authorization: Bearer $MK" -H 'Content-Type: application/json' \
  -d '{"model_name":"badmodel","litellm_params":{"model":"openai/badmodel","api_base":"http://badmodel-stub:8080/v1","api_key":"sk-stub"}}' | head -c 300
echo

echo "== 3. holmes 换面 MODEL=badmodel（原容器 stop+rename 保留） =="
docker stop holmesgpt-am1
docker rename holmesgpt-am1 holmesgpt-am1-hold
docker rm -f holmesgpt-am1 2>/dev/null || true
docker run -d --name holmesgpt-am1 --network alert-net --network-alias holmes \
  -e HOLMES_PORT=8080 -e HOLMES_API_KEY="$HOLMES_KEY" -e MODEL=openai/badmodel \
  -e OPENAI_API_BASE=http://litellm:4000/v1 -e OPENAI_API_KEY="$RUN_KEY" \
  -e PROMETHEUS_URL=http://prometheus-am0:9090 -e HOME=/tmp \
  --memory 1536m local/holmesgpt:am1-http
echo "-- 等待 holmes 就绪（healthz，最多 90s） --"
i=0
until docker run --rm --network alert-net curlimages/curl:8.8.0 -s -o /dev/null -w '%{http_code}' http://holmes:8080/healthz 2>/dev/null | grep -q 200; do
  i=$((i+1)); [ "$i" -gt 30 ] && { echo "holmes NOT READY"; exit 1; }
  sleep 3
done
echo "holmes ready"

echo "== 4. 冒烟：直接调 holmes（期望 200 + analysis = 散文垃圾） =="
docker run --rm --network alert-net curlimages/curl:8.8.0 -s -m 60 -X POST http://holmes:8080/api/chat \
  -H "X-API-Key: $HOLMES_KEY" -H 'Content-Type: application/json' \
  -d '{"ask":"reply pong"}' | head -c 400
echo

echo "== 5. 启动 S3 单场景批（新 control-app 镜像含 scorer 修复） =="
docker rm -f eval-runner-am3-e2e03-0905 2>/dev/null || true
nohup sh /tmp/cc-m330-evalrun.sh deploy/alert/eval/eval-scenarios-s3.yml am3-e2e03-0905 \
  > /tmp/m330-am3-e2e03-0905-run.log 2>&1 &
sleep 5
docker ps --filter name=eval-runner-am3-e2e03-0905 --format '{{.Names}} {{.Status}}'
exit 0
