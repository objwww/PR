#!/bin/sh
# e2e-demo-20260913 前后端联通矩阵（195 本机执行）
# 前提：.env 已临时替换 AUTH_OPERATOR_PASSWORD_BCRYPT 为演示口令哈希并重建 control-app
# 产出：仅状态码与字节数，无任何密钥/载荷落盘
set -u
BASE=http://127.0.0.1:8090
JAR=/tmp/demo-cookies.txt
OUT="${1:-/tmp/demo-matrix.txt}"
USER_NAME=$(grep '^AUTH_OPERATOR_USERNAME=' /opt/build/pr/deploy/.env | cut -d= -f2)
PASS='E2eDemo#20260913'
rm -f "$JAR"
{
echo "== 0. 静态资源与未认证门禁 =="
curl -s -o /dev/null -w "GET /                     -> %{http_code} (%{size_download}B)\n" $BASE/
curl -s -o /dev/null -w "GET /login                -> %{http_code}\n" $BASE/login
curl -s -o /dev/null -w "GET /overview(SPA回退)    -> %{http_code}\n" $BASE/overview
curl -s -o /dev/null -w "未认证 GET /api/v1/overview/summary -> %{http_code}（期望401）\n" $BASE/api/v1/overview/summary

echo "== 1. 登录 =="
curl -s -c "$JAR" -o /dev/null $BASE/api/auth/csrf
XSRF=$(grep 'XSRF-TOKEN' "$JAR" | awk '{print $NF}')
CODE=$(curl -s -b "$JAR" -c "$JAR" -o /tmp/demo-login.resp -w "%{http_code}" -X POST $BASE/api/auth/login -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASS")
echo "POST /auth/login -> $CODE"
[ "$CODE" = "200" ] || { echo "登录失败，矩阵中止"; exit 1; }
curl -s -b "$JAR" -o /dev/null -w "GET /auth/me -> %{http_code}\n" $BASE/api/auth/me

echo "== 2. 已认证 GET 矩阵（25 路由的后端依赖面） =="
for p in \
  /api/v1/overview/summary \
  "/api/v1/incidents?page=0&size=5" \
  /api/v1/incidents/facets \
  /api/v1/incidents/summary \
  "/api/v1/incidents?status=RESOLVED&size=3" \
  "/api/rca-runs?size=5" \
  /api/agent-ops/summary \
  /api/agent-ops/workers \
  "/api/metrics/query_range?query=up&start=2026-09-13T07:00:00Z&end=2026-09-13T07:10:00Z&step=60" \
  "/api/cases?page=0&size=5" \
  /api/cases/summary \
  /api/duty/schedule \
  /api/duty/schedule/snapshot \
  /api/duty/members \
  /api/duty/channels \
  /api/duty/overrides \
  "/api/duty/notifications?page=0&size=5" \
  /api/duty/notifications/feed \
  "/api/eval/runs?page=0&size=5" \
  /api/eval/datasets \
  /api/eval/reviews/assignments \
  /api/eval/reviews/disagreements \
  "/api/eval/compare?baseline=x&candidate=y" \
  /api/release-assets \
  /api/drills \
  /api/drills/templates \
  /api/mcp-servers \
  ; do
  curl -s -b "$JAR" -o /tmp/demo-ep.resp -w "GET %{url_effective} -> %{http_code} (%{size_download}B)\n" "$BASE$p" | sed "s|$BASE||"
done

echo "== 3. 详情类（取真实 id 逐层下钻） =="
RID=$(curl -s -b "$JAR" "$BASE/api/rca-runs?size=1" | sed -n 's/.*"runId":"\([a-f0-9-]*\)".*/\1/p' | head -1)
echo "样本 run=$RID"
for p in "/api/rca-runs/$RID" "/api/rca-runs/$RID/events" "/api/rca-runs/$RID/report"; do
  curl -s -b "$JAR" -o /dev/null -w "GET $p -> %{http_code} (%{size_download}B)\n" "$BASE$p"
done
IID=$(curl -s -b "$JAR" "$BASE/api/v1/incidents?size=1" | sed -n 's/.*"incidentId":"\([a-f0-9-]*\)".*/\1/p' | head -1)
echo "样本 incident=$IID"
curl -s -b "$JAR" -o /dev/null -w "GET /api/v1/incidents/$IID -> %{http_code} (%{size_download}B)\n" "$BASE/api/v1/incidents/$IID"
EID=$(curl -s -b "$JAR" "$BASE/api/eval/runs?size=1" | sed -n 's/.*"runId":"\([a-f0-9-]*\)".*/\1/p' | head -1)
echo "样本 eval run=$EID"
for p in "/api/eval/runs/$EID" "/api/eval/runs/$EID/cases" "/api/eval/runs/$EID/usage" "/api/eval/runs/$EID/review-progress"; do
  curl -s -b "$JAR" -o /dev/null -w "GET $p -> %{http_code} (%{size_download}B)\n" "$BASE$p"
done

echo "== 4. SSE 票据面 =="
curl -s -b "$JAR" -o /dev/null -w "GET /api/rca-runs/$RID/events/stream-ticket -> %{http_code}\n" "$BASE/api/rca-runs/$RID/events/stream-ticket"

echo "== 5. 登出 =="
XSRF2=$(grep 'XSRF-TOKEN' "$JAR" | awk '{print $NF}')
curl -s -b "$JAR" -c "$JAR" -o /dev/null -w "POST /auth/logout -> %{http_code}\n" -X POST $BASE/api/auth/logout -H "X-XSRF-TOKEN: $XSRF2"
curl -s -b "$JAR" -o /dev/null -w "登出后 GET /api/v1/overview/summary -> %{http_code}（期望401）\n" $BASE/api/v1/overview/summary
rm -f "$JAR" /tmp/demo-login.resp /tmp/demo-ep.resp
} | tee "$OUT"
