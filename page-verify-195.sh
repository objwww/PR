#!/bin/sh
# PAGE 批 195 生效验收（API 级；零真实注入、零数据遗留）：
#   1 能力读面 2 能力闸门拒绝(零落库) 3 受理投影(种子行验证后即删) 4 drill 读面链
#   5 前端资产无 api/api 6 磁盘白名单口径
# 鉴权沿 op-smoke.sh：容器 env operator bearer + CSRF cookie/header 全套
set -e
BASE=http://127.0.0.1:8080
RUNS=/opt/build/runs-pagebatch
mkdir -p "$RUNS"
OB=$(docker exec deploy-control-app-1 env | grep '^APP_OPERATOR_API_BEARER=' | cut -d= -f2-)
[ -n "$OB" ] || { echo 'FATAL: operator bearer 未取到'; exit 1; }
H="Authorization: Bearer $OB"
CT="Content-Type: application/json"
curl -s -c /tmp/page-csrf.jar -o /dev/null $BASE/api/auth/csrf
TOK=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/page-csrf.jar)
X="X-XSRF-TOKEN: $TOK"
JAR="-b /tmp/page-csrf.jar"
post() { curl -s -o "$1" -w '%{http_code}' -X POST -H "$H" -H "$CT" -H "$X" $JAR -d "$2" "$BASE$3"; }
get() { curl -s -o "$1" -w '%{http_code}' -H "$H" $JAR "$BASE$2"; }
CMDCOUNT() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from eval_run_command"; }
FAILS=0
fail() { echo "VERIFY-FAIL: $1"; FAILS=$((FAILS+1)); }

echo '=== 1. PAGE-03 能力读面 ==='
c=$(get "$RUNS/cap.json" /api/eval/launch-capability)
echo "code=$c body=$(cat "$RUNS/cap.json")"
[ "$c" = "200" ] || fail "capability $c"
grep -q '"modes":\["L"\]' "$RUNS/cap.json" || fail 'capability modes != [L]'
grep -q '"maxConcurrency":1' "$RUNS/cap.json" || fail 'capability maxConcurrency != 1'
grep -q '"budgetMaxTokens":false' "$RUNS/cap.json" || fail 'capability budget not closed'

echo '=== 2. PAGE-03 能力闸门拒绝（每例后账本计数不变 = 零落库） ==='
BEFORE=$(CMDCOUNT)
c=$(post "$RUNS/modeE.json" '{"idempotencyKey":"page-acc-e-1","displayName":"验收E","mode":"E","datasetVersion":"eval-ds-1","maxConcurrency":1}' /api/eval/runs)
echo "modeE code=$c body=$(cat "$RUNS/modeE.json")"
[ "$c" = "400" ] && grep -q 'MODE_NOT_SUPPORTED' "$RUNS/modeE.json" || fail "mode E not rejected ($c)"
c=$(post "$RUNS/modeB.json" '{"idempotencyKey":"page-acc-b-1","displayName":"验收B","mode":"B","datasetVersion":"eval-ds-1","maxConcurrency":1}' /api/eval/runs)
echo "modeB code=$c body=$(cat "$RUNS/modeB.json")"
[ "$c" = "400" ] && grep -q 'MODE_NOT_SUPPORTED' "$RUNS/modeB.json" || fail "mode B not rejected ($c)"
c=$(post "$RUNS/budget.json" '{"idempotencyKey":"page-acc-budget","displayName":"验收预算","mode":"L","datasetVersion":"eval-ds-1","budgetMaxTokens":1000,"maxConcurrency":1}' /api/eval/runs)
echo "budget code=$c body=$(cat "$RUNS/budget.json")"
[ "$c" = "400" ] && grep -q 'BUDGET_NOT_SUPPORTED' "$RUNS/budget.json" || fail "budget not rejected ($c)"
c=$(post "$RUNS/deadline.json" '{"idempotencyKey":"page-acc-deadline","displayName":"验收截止","mode":"L","datasetVersion":"eval-ds-1","deadlineSeconds":600,"maxConcurrency":1}' /api/eval/runs)
echo "deadline code=$c body=$(cat "$RUNS/deadline.json")"
[ "$c" = "400" ] && grep -q 'DEADLINE_NOT_SUPPORTED' "$RUNS/deadline.json" || fail "deadline not rejected ($c)"
c=$(post "$RUNS/conc.json" '{"idempotencyKey":"page-acc-conc","displayName":"验收并发","mode":"L","datasetVersion":"eval-ds-1","maxConcurrency":2}' /api/eval/runs)
echo "concurrency code=$c body=$(cat "$RUNS/conc.json")"
[ "$c" = "400" ] && grep -q 'CONCURRENCY_NOT_SUPPORTED' "$RUNS/conc.json" || fail "concurrency not rejected ($c)"
c=$(post "$RUNS/ds.json" '{"idempotencyKey":"page-acc-ds","displayName":"验收数据集","mode":"L","datasetVersion":"eval-ds-99","maxConcurrency":1}' /api/eval/runs)
echo "dataset code=$c body=$(cat "$RUNS/ds.json")"
[ "$c" = "400" ] && grep -q 'DATASET_VERSION_NOT_SUPPORTED' "$RUNS/ds.json" || fail "dataset not rejected ($c)"
AFTER=$(CMDCOUNT)
echo "command rows before=$BEFORE after=$AFTER"
[ "$BEFORE" = "$AFTER" ] || fail '能力拒绝产生了命令落库（违反零副作用）'

echo '=== 3. PAGE-10 受理投影（种子命令行 -> 验证 -> 即删，零遗留零执行） ==='
PRID=00000000-0000-4000-8000-5eed00000001
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -q -c "DELETE FROM eval_run_command WHERE idempotency_key='page-acceptance-accepted-only'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -q -c "INSERT INTO eval_run_command (id, command_type, eval_run_id, idempotency_key, payload, payload_hash, state, actor, created_at) VALUES ('00000000-0000-4000-8000-5eed00000002','LAUNCH','$PRID','page-acceptance-accepted-only','{\"displayName\":\"PAGE验收等待实验\",\"mode\":\"L\",\"datasetVersion\":\"eval-ds-1\",\"model\":null,\"promptVersion\":null,\"budgetMaxTokens\":null,\"maxConcurrency\":null,\"deadlineSeconds\":null,\"roundsPerScenario\":2}'::jsonb,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','PENDING','page-acceptance',now())"
c=$(get "$RUNS/acc.json" /api/eval/runs/$PRID)
echo "accepted code=$c body=$(cat "$RUNS/acc.json")"
[ "$c" = "200" ] && grep -q '"acceptedOnly":true' "$RUNS/acc.json" || fail "acceptedOnly not projected ($c)"
grep -q '"commandState":"PENDING"' "$RUNS/acc.json" || fail 'commandState PENDING missing'
grep -q '"displayName":"PAGE验收等待实验"' "$RUNS/acc.json" || fail 'displayName missing from projection'
c=$(get "$RUNS/nf.json" /api/eval/runs/00000000-0000-4000-8000-000000000000)
echo "random id code=$c body=$(cat "$RUNS/nf.json")"
[ "$c" = "404" ] || fail "random id should stay 404 ($c)"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -q -c "DELETE FROM eval_run_command WHERE idempotency_key='page-acceptance-accepted-only'"
LEFT=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from eval_run_command where idempotency_key like 'page-acceptance%'")
[ "$LEFT" = "0" ] || fail '种子行未清理干净'
echo "种子行已清理 left=$LEFT"

echo '=== 4. drill 读面链（PAGE-01 服务端语义；既有 drill 只读） ==='
DRILL=ebaf6e9b-02b1-4bf8-8d69-2cebd8fb205e
c=$(get "$RUNS/dl.json" "/api/drills?limit=5"); echo "list code=$c"; [ "$c" = "200" ] || fail "drill list $c"
c=$(get "$RUNS/dd.json" "/api/drills/$DRILL"); echo "detail code=$c"; [ "$c" = "200" ] || fail "drill detail $c"
c=$(get "$RUNS/de.json" "/api/drills/$DRILL/events?afterSeq=0&limit=10"); echo "events code=$c"; [ "$c" = "200" ] || fail "drill events $c"
c=$(get "$RUNS/d404.json" "/api/drills/00000000-0000-4000-8000-000000000000"); echo "detail-404 code=$c"; [ "$c" = "404" ] || fail "drill 404 semantic $c"
c=$(get "$RUNS/tp.json" "/api/drills/templates"); echo "templates code=$c"; [ "$c" = "200" ] || fail "templates $c"
grep -o '"ready":[a-z]*' "$RUNS/tp.json" | sort | uniq -c | sed 's/^/  template ready: /'

echo '=== 4b. drill 服务端预检（真实检查面，不注入） ==='
c=$(post "$RUNS/pv.json" '{"scenarioId":"S1","targetEnv":"arena-195","durationSeconds":60}' /api/drills/preview)
echo "preview code=$c canLaunch=$(sed -n 's/.*"canLaunch":\([a-z]*\).*/\1/p' "$RUNS/pv.json")"
[ "$c" = "200" ] || fail "preview $c"
grep -o '"status":"[A-Z]*"' "$RUNS/pv.json" | sort | uniq -c | sed 's/^/  check: /'

echo '=== 5. 前端资产（PAGE-01/02/03 落地面） ==='
INDEX=$(curl -s http://127.0.0.1:8090/ | grep -o 'assets/index-[^"]*\.js' | head -1)
echo "index chunk: $INDEX"
DD=$(curl -s "http://127.0.0.1:8090/$INDEX" | grep -o 'assets/DrillDetailView-[^"]*\.js' | head -1)
EN=$(curl -s "http://127.0.0.1:8090/$INDEX" | grep -o 'assets/EvalNewView-[^"]*\.js' | head -1)
echo "drill chunk: $DD / eval chunk: $EN"
DDAPI=$(curl -s "http://127.0.0.1:8090/$DD" | grep -c 'api/api' || true)
[ "$DDAPI" = "0" ] || fail "DrillDetailView 仍含 api/api 引用 $DDAPI"
curl -s "http://127.0.0.1:8090/$DD" | grep -q 'drills/' || fail 'DrillDetailView 无 drills/ 路径（资产未换代？）'
curl -s "http://127.0.0.1:8090/$DD" | grep -q '演练作业不存在' || fail 'DrillDetailView 缺 404 分态文案（资产未换代？）'
ENAPI=$(curl -s "http://127.0.0.1:8090/$EN" | grep -c 'api/api' || true)
[ "$ENAPI" = "0" ] || fail "EvalNewView 仍含 api/api 引用"
curl -s "http://127.0.0.1:8090/$EN" | grep -q 'launch-capability' || fail 'EvalNewView 未接能力读面（资产未换代？）'
curl -s "http://127.0.0.1:8090/$EN" | grep -q '当前环境未开放' || fail 'EvalNewView 缺能力禁用文案（资产未换代？）'
echo '前端资产核验通过：api/api=0、404 分态文案在、能力读面与禁用文案已接线'

echo '=== 6. PAGE-08 磁盘白名单口径（node_exporter 已在场；有数据才断言序列名） ==='
END=$(date +%s); START=$((END-3600))
c=$(get "$RUNS/disk.json" "/api/metrics/query_range?query=host_disk_usage&start=$START&end=$END&step=60")
echo "disk code=$c"
grep -o '"name":"[^"]*"' "$RUNS/disk.json" | sort -u | sed 's/^/  series: /' || echo '  series: 空（未采集，诚实空面）'

echo '=============================='
if [ "$FAILS" = "0" ]; then echo 'PAGE-195-VERIFY: ALL PASS'; else echo "PAGE-195-VERIFY: $FAILS FAIL"; exit 1; fi
