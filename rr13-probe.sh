#!/bin/sh
# RR13（OR-04）：角色×端点零突变探针——读面 + 角色层拒绝写（403 在任何副作用前）
set -u
OUT=/tmp/rr13
rm -rf "$OUT"; mkdir -p "$OUT"
BASE="http://127.0.0.1:8080"
ENVF=/opt/build/pr/deploy/.env

OP_B=$(grep -E '^APP_OPERATOR_API_BEARER=' "$ENVF" | head -1 | cut -d= -f2-)
RL_B=$(grep -E '^APP_RELEASE_API_BEARER=' "$ENVF" | head -1 | cut -d= -f2-)
DA_B=$(grep -E '^APP_DUTY_ADAPTER_BEARER=' "$ENVF" | head -1 | cut -d= -f2-)
WH_B=$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' "$ENVF" | head -1 | cut -d= -f2-)
[ -n "$OP_B" ] && [ -n "$RL_B" ] && [ -n "$DA_B" ] || { echo "FAIL: bearer 缺件"; exit 1; }
echo "bearer 装配: op=${#OP_B}B release=${#RL_B}B duty=${#DA_B}B webhook=${#WH_B}B（只显长度）"
echo "INPUTCAPTURE 当前 .env 值: [$(grep -E '^APP_ALERT_R7_INPUTCAPTURE=' "$ENVF" | head -1 | cut -d= -f2-)]"

RUN26=55e575e5-bdac-4667-8947-9da821b26980
probe() { # name method url [bearer] [data]
  _n="$1"; _m="$2"; _u="$3"; _b="${4:-}"; _d="${5:-}"
  if command -v curl >/dev/null 2>&1; then
    if [ -n "$_b" ]; then
      if [ -n "$_d" ]; then
        CODE=$(curl -s -o /dev/null -w '%{http_code}' -X "$_m" -H "Authorization: Bearer $_b" -H 'Content-Type: application/json' -d "$_d" "$_u")
      else
        CODE=$(curl -s -o /dev/null -w '%{http_code}' -X "$_m" -H "Authorization: Bearer $_b" "$_u")
      fi
    else
      CODE=$(curl -s -o /dev/null -w '%{http_code}' -X "$_m" "$_u")
    fi
  else
    echo "curl 不可用"; exit 1
  fi
  echo "$CODE" >> "$OUT/$_n.code"
  echo "$_n => $CODE"
}

echo "== RR13 探针（期望码在括号内）=="
probe p01_noauth_eval        GET  "$BASE/api/eval/runs"                      ""        ; echo "  期望 401"
probe p02_operator_eval      GET  "$BASE/api/eval/runs"                      "$OP_B"   ; echo "  期望 200"
probe p03_release_eval       GET  "$BASE/api/eval/runs"                      "$RL_B"   ; echo "  期望 403"
probe p04_webhook_eval       GET  "$BASE/api/eval/runs"                      "$WH_B"   ; echo "  期望 403（或 401 若 webhook 键缺失）"
probe p05_release_cfgactive  GET  "$BASE/api/config-bundles/active"          "$RL_B"   ; echo "  期望 200"
probe p06_operator_cfgactive GET  "$BASE/api/config-bundles/active"          "$OP_B"   ; echo "  期望 403"
probe p07_operator_cfgpost   POST "$BASE/api/config-bundles"                 "$OP_B" '{}' ; echo "  期望 403（角色层拒绝，零副作用）"
probe p08_duty_read          GET  "$BASE/api/duty/notifications"             "$DA_B"   ; echo "  期望 403（I6 单面）"
probe p09_logout_nocsrf      POST "$BASE/api/auth/logout"                   ""        ; echo "  期望 403（CSRF 保留面）"
probe p10_sse_noticket       GET  "$BASE/api/rca-runs/$RUN26/events/stream"  ""        ; echo "  期望 401/400（票必经）"
probe p11_operator_me        GET  "$BASE/api/auth/me"                        "$OP_B"   ; echo "  期望 200"

echo "== 断言 =="
python3 - <<'PY'
import os
d = "/tmp/rr13"
exp = {
 "p01_noauth_eval":"401", "p02_operator_eval":"200", "p03_release_eval":"403",
 "p05_release_cfgactive":"200", "p06_operator_cfgactive":"403",
 "p07_operator_cfgpost":"403", "p08_duty_read":"403", "p09_logout_nocsrf":"403",
 "p11_operator_me":"200",
}
ok = True
for name, want in exp.items():
    p = os.path.join(d, name + ".code")
    got = open(p).read().strip() if os.path.exists(p) else "MISSING"
    mark = "PASS" if got == want else "FAIL"
    if got != want: ok = False
    print(f"  {name:26s} got={got} want={want} {mark}")
# 软断言：webhook 线与 SSE 票
for name, wantset in {"p04_webhook_eval": {"403","401"}, "p10_sse_noticket": {"401","400","403"}}.items():
    p = os.path.join(d, name + ".code")
    got = open(p).read().strip() if os.path.exists(p) else "MISSING"
    mark = "PASS" if got in wantset else "FAIL"
    if got not in wantset: ok = False
    print(f"  {name:26s} got={got} want∈{sorted(wantset)} {mark}")
print("RR13-OVERALL:", "PASS" if ok else "FAIL")
PY
echo "RR13-DONE"
