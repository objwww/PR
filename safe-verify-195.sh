#!/bin/sh
# SAFE 批 195 生效验收：
#   1 能力读面 launchEnabled:false + L 发起 400 LAUNCH_DISABLED（账本零新增）
#   2 演练目录 ready=false + create 409 LAUNCH_DISABLED（作业表零新增）+ e03 占位不变
#   3 CSRF 真实浏览器会话链四变体（修复核心负例：错误令牌 403）
#   4 前端资产含能力禁用文案
# 日志不回显 bearer/密码（server-side 取 env）。
set -e
BASE=http://127.0.0.1:8080
RUNS=/opt/build/runs-safebatch
mkdir -p "$RUNS"
OB=$(docker exec deploy-control-app-1 env | grep '^APP_OPERATOR_API_BEARER=' | cut -d= -f2-)
[ -n "$OB" ] || { echo 'FATAL: env 未取到'; exit 1; }
H="Authorization: Bearer $OB"
CT="Content-Type: application/json"
FAILS=0
fail() { echo "VERIFY-FAIL: $1"; FAILS=$((FAILS+1)); }

echo '=== 1. SAFE-02 能力读面与 L 发起拒绝 ==='
curl -s -c /tmp/sv-csrf.jar -o /dev/null $BASE/api/auth/csrf
TOK=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/sv-csrf.jar)
X="X-XSRF-TOKEN: $TOK"
curl -s -H "$H" -o "$RUNS/cap.json" $BASE/api/eval/launch-capability
grep -q '"launchEnabled":false' "$RUNS/cap.json" || fail 'capability launchEnabled != false'
BEFORE=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from eval_run_command")
code=$(curl -s -o "$RUNS/launch.json" -w '%{http_code}' -X POST -H "$H" -H "$CT" -H "$X" -b /tmp/sv-csrf.jar \
  -d '{"idempotencyKey":"safe-verify-l-1","displayName":"SAFE验收L","mode":"L","datasetVersion":"eval-ds-1","maxConcurrency":1}' \
  $BASE/api/eval/runs)
echo "launch code=$code body=$(cat "$RUNS/launch.json")"
[ "$code" = "400" ] && grep -q 'LAUNCH_DISABLED' "$RUNS/launch.json" || fail "L launch should 400 LAUNCH_DISABLED ($code)"
AFTER=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from eval_run_command")
echo "command rows before=$BEFORE after=$AFTER"
[ "$BEFORE" = "$AFTER" ] || fail 'L 拒绝产生命令落库'

echo '=== 2. SAFE-04 演练启动面关闭 ==='
curl -s -H "$H" -o "$RUNS/tp.json" $BASE/api/drills/templates
RC=$(grep -o '"ready":false' "$RUNS/tp.json" | wc -l)
echo "templates ready:false x $RC"
[ "$RC" -ge 5 ] || fail "模板 ready=false 数量 $RC < 5"
grep -q 'SAFE-04' "$RUNS/tp.json" || fail '模板 reason 未指向能力位'
DRILLS_BEFORE=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from drill_job")
code=$(curl -s -o "$RUNS/create.json" -w '%{http_code}' -X POST -H "$H" -H "$CT" -H "$X" -b /tmp/sv-csrf.jar \
  -d '{"scenarioId":"S1","targetEnv":"arena-195","idempotencyKey":"safe-verify-drill-1","durationSeconds":60}' \
  $BASE/api/drills)
echo "drill create code=$code body=$(cat "$RUNS/create.json")"
[ "$code" = "409" ] && grep -q 'LAUNCH_DISABLED\|启动面当前已关闭' "$RUNS/create.json" || fail "drill create should 409 launch-disabled ($code)"
DRILLS_AFTER=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from drill_job")
echo "drill rows before=$DRILLS_BEFORE after=$DRILLS_AFTER"
[ "$DRILLS_BEFORE" = "$DRILLS_AFTER" ] || fail '演练拒绝产生了作业行'
curl -s -H "$H" -o "$RUNS/e03.json" $BASE/api/drills/ebaf6e9b-02b1-4bf8-8d69-2cebd8fb205e
grep -q '"state":"RECOVERY_FAILED"' "$RUNS/e03.json" || fail 'e03 占位状态被改动'

echo '=== 3. SAFE-01 CSRF 过滤链行为（bearer 认证 + cookie jar；浏览器会话四变体已由'
echo '    MockMvc 全覆盖，生产面无明文口令不作登录复刻；此处验证同一 CsrfFilter 链） ==='
JAR=/tmp/sv-browser.jar; rm -f $JAR
curl -s -c $JAR -o /dev/null $BASE/api/auth/csrf
BTOK=$(awk '$6=="XSRF-TOKEN"{print $7}' $JAR)
[ -n "$BTOK" ] || { echo 'FATAL: 引导无 XSRF cookie'; exit 1; }

code=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -b $JAR -X POST -H "$CT" \
  -d '{"scenarioId":"S1","targetEnv":"arena-195","idempotencyKey":"safe-csrf-noheader"}' \
  $BASE/api/drills)
echo "csrf: cookie jar, NO header -> $code"
[ "$code" = "403" ] || fail '无 CSRF 头应 403'

code=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -b $JAR -X POST -H "$CT" -H "X-XSRF-TOKEN: forged-wrong-value" \
  -d '{"scenarioId":"S1","targetEnv":"arena-195","idempotencyKey":"safe-csrf-wrong"}' \
  $BASE/api/drills)
echo "csrf: cookie jar, WRONG header -> $code"
[ "$code" = "403" ] || fail '错误令牌应 403（SAFE-01 修复核心负例）'

code=$(curl -s -o "$RUNS/csrf-ok.json" -w '%{http_code}' -H "$H" -b $JAR -X POST -H "$CT" -H "X-XSRF-TOKEN: $BTOK" \
  -d '{"scenarioId":"S1","targetEnv":"arena-195","idempotencyKey":"safe-csrf-right"}' \
  $BASE/api/drills)
echo "csrf: cookie jar, CORRECT header -> $code body=$(cat "$RUNS/csrf-ok.json")"
[ "$code" = "409" ] && grep -q '启动面当前已关闭' "$RUNS/csrf-ok.json" || fail "正确令牌链应通过 CSRF 到达服务端拒绝面（$code）"

code=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -X POST -H "$CT" -H "X-XSRF-TOKEN: $BTOK" \
  -d '{"scenarioId":"S1","targetEnv":"arena-195","idempotencyKey":"safe-csrf-nocookie"}' \
  $BASE/api/drills)
echo "csrf: header only, NO cookie -> $code"
[ "$code" = "403" ] || fail '仅头无 cookie 应 403'

echo '=== 4. 前端资产（EvalNewView 能力禁用文案） ==='
INDEX=$(curl -s http://127.0.0.1:8090/ | grep -o 'assets/index-[^"]*\.js' | head -1)
EN=$(curl -s "http://127.0.0.1:8090/$INDEX" | grep -o 'assets/EvalNewView-[^"]*\.js' | head -1)
curl -s "http://127.0.0.1:8090/$EN" | grep -q '评测发起当前已关闭' || fail 'EvalNewView 资产未换代（缺禁用文案）'
echo "eval chunk: $EN（含能力禁用文案）"

echo '=============================='
if [ "$FAILS" = "0" ]; then echo 'SAFE-195-VERIFY: ALL PASS'; else echo "SAFE-195-VERIFY: $FAILS FAIL"; exit 1; fi
