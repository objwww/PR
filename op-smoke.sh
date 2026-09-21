#!/bin/sh
# OP 批端点 smoke v2（195 真机）：鉴权=operator bearer + CSRF cookie/header 全套
# （operator 写面保留 CSRF=平台既有姿态；GET 免 CSRF）
BASE=http://127.0.0.1:8080
RUNS=/opt/build/runs-opbatch
mkdir -p "$RUNS"
OB=$(docker exec deploy-control-app-1 env | grep '^APP_OPERATOR_API_BEARER=' | cut -d= -f2-)
[ -n "$OB" ] || { echo 'FATAL: operator bearer 未取到'; exit 1; }
H="Authorization: Bearer $OB"
CT="Content-Type: application/json"
curl -s -c /tmp/op-csrf.jar -o /dev/null $BASE/api/auth/csrf
TOK=$(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/op-csrf.jar)
X="X-XSRF-TOKEN: $TOK"
JAR="-b /tmp/op-csrf.jar"
fail() { echo "SMOKE-FAIL: $1"; exit 1; }
post() { curl -s -o "$1" -w '%{http_code}' -X POST -H "$H" -H "$CT" -H "$X" $JAR -d "$2" "$BASE$3"; }

echo '=== 1. 未认证面：GET 401 / POST 403（CSRF 先于鉴权拒绝） ==='
c1=$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/agent-ops/action-assessment)
c2=$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/eval/regression-candidates)
c3=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$CT" -d '{}' $BASE/api/eval/regression-candidates)
echo "unauth: ops-get=$c1 eval-get=$c2 eval-post=$c3"
[ "$c1" = 401 ] && [ "$c2" = 401 ] && [ "$c3" = 403 ] || fail "未认证面不齐"

echo '=== 2. action-assessment（新读面，bearer GET） ==='
c=$(curl -s -o "$RUNS/aa.out" -w '%{http_code}' -H "$H" $BASE/api/agent-ops/action-assessment)
echo "code=$c"; cat "$RUNS/aa.out"; echo
[ "$c" = 200 ] || fail "action-assessment $c"

echo '=== 3. quality 404（不存在的 eval run） ==='
c=$(curl -s -o "$RUNS/q404.out" -w '%{http_code}' -H "$H" $BASE/api/eval/runs/00000000-0000-0000-0000-000000000000/quality)
echo "code=$c body=$(cat "$RUNS/q404.out")"
[ "$c" = 404 ] || fail "quality 404 got $c"

echo '=== 4. 选真实 终态run+已验证报告 ==='
ROW=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.id||' '||rep.id from rca_run r join rca_report rep on rep.run_id=r.id where rep.validation_status='STRUCTURE_VALIDATED' order by rep.created_at desc limit 1;")
RUN=${ROW%% *}; REP=${ROW##* }
echo "run=$RUN report=$REP"
[ -n "$RUN" ] && [ "$RUN" != "$REP" ] || fail "无可用的已验证报告"

echo '=== 5. 回归锁：缺 reportId → 400（真机 parseId NPE 修复面） ==='
c=$(post "$RUNS/brq.out" '{"caseKey":"c","scenarioFamilyId":"f"}' /api/eval/regression-candidates)
echo "missing-reportId=$c body=$(cat "$RUNS/brq.out")"
[ "$c" = 400 ] || fail "缺 reportId 应 400 got $c"

echo '=== 6. feedback 201 → 重放 200 → 摘要不符 409 → 列表 200 ==='
TS=$(date +%s)
BODY="{\"verdict\":\"PARTIAL\",\"reason\":\"smoke: 主因定位正确但证据引用不全\",\"evidenceRefs\":[],\"idempotencyKey\":\"op-smoke-$REP-$TS\"}"
c=$(post "$RUNS/fb1.out" "$BODY" /api/rca-runs/$RUN/report/$REP/feedback)
echo "fresh=$c"; cat "$RUNS/fb1.out"; echo
[ "$c" = 201 ] || fail "feedback fresh $c"
c=$(post "$RUNS/fb2.out" "$BODY" /api/rca-runs/$RUN/report/$REP/feedback)
echo "replay=$c replayed=$(sed -n 's/.*"replayed":\([a-z]*\).*/\1/p' "$RUNS/fb2.out")"
[ "$c" = 200 ] || fail "feedback replay $c"
c=$(post "$RUNS/fb3.out" "{\"verdict\":\"ACCEPTED\",\"expectedReportDigest\":\"deadbeef\",\"idempotencyKey\":\"op-smoke-$REP-dm-$TS\"}" /api/rca-runs/$RUN/report/$REP/feedback)
echo "digest-mismatch=$c body=$(cat "$RUNS/fb3.out")"
[ "$c" = 409 ] || fail "digest mismatch $c"
c=$(curl -s -o "$RUNS/fb4.out" -w '%{http_code}' -H "$H" $BASE/api/rca-runs/$RUN/report/$REP/feedback)
echo "list=$c"; head -c 200 "$RUNS/fb4.out"; echo
[ "$c" = 200 ] || fail "feedback list $c"
FBID=$(sed -n 's/.*"id":"\([0-9a-f-]*\)".*/\1/p' "$RUNS/fb1.out" | head -1)
echo "feedbackId=$FBID"

echo '=== 7. 候选 202 → 重放 200 → 审核 202 → 物化 201 ==='
PB="{\"reportId\":\"$REP\",\"feedbackId\":\"$FBID\",\"caseKey\":\"op-smoke-$RUN-$TS\",\"scenarioFamilyId\":\"op-smoke-family\"}"
c=$(post "$RUNS/cd1.out" "$PB" /api/eval/regression-candidates)
echo "propose=$c"; cat "$RUNS/cd1.out"; echo
[ "$c" = 202 ] || fail "propose $c"
c=$(post "$RUNS/cd2.out" "$PB" /api/eval/regression-candidates)
echo "replay=$c replayed=$(sed -n 's/.*"replayed":\([a-z]*\).*/\1/p' "$RUNS/cd2.out")"
[ "$c" = 200 ] || fail "propose replay $c"
CID=$(sed -n 's/.*"candidateId":"\([0-9a-f-]*\)".*/\1/p' "$RUNS/cd1.out" | head -1)
echo "candidateId=$CID"
c=$(post "$RUNS/rv1.out" '{"verdict":"ACCEPTED_FOR_CANDIDATE","reason":"smoke: 证据链完整可入集"}' /api/eval/regression-candidates/$CID/reviews)
echo "review=$c state=$(sed -n 's/.*"state":"\([A-Z_]*\)".*/\1/p' "$RUNS/rv1.out" | head -1)"
[ "$c" = 202 ] || fail "review $c"
MB="{\"datasetName\":\"op-smoke-ds\",\"datasetVersion\":\"v1\",\"partition\":\"TUNING\",\"expectedRootCause\":{\"component\":\"checkout\",\"faultType\":\"config_error\",\"reasonCode\":\"RC_CONFIG\"},\"expectedSymptomCodes\":[\"SYM_LATENCY\"]}"
c=$(post "$RUNS/mt1.out" "$MB" /api/eval/regression-candidates/$CID/materialize)
echo "materialize=$c"; cat "$RUNS/mt1.out"; echo
[ "$c" = 201 ] || fail "materialize $c"
c=$(post "$RUNS/mt2.out" "$MB" /api/eval/regression-candidates/$CID/materialize)
echo "materialize-replay=$c replayed=$(sed -n 's/.*"replayed":\([a-z]*\).*/\1/p' "$RUNS/mt2.out")"
[ "$c" = 200 ] || fail "materialize replay $c"

echo '=== 8. DB 对账（四表 + 物化行） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select 'report_feedback', count(*) from report_feedback union all select 'rca_regression_candidate', count(*) from rca_regression_candidate union all select 'rca_regression_review', count(*) from rca_regression_review union all select 'rca_action_assessment', count(*) from rca_action_assessment;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select d.name, d.version, c.case_key, c.scenario_family_id from dataset_version d join case_version c on c.dataset_version_id = d.id where d.name='op-smoke-ds';"

echo '=== 9. 容器 ERROR 复核 ==='
E=$(docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c ERROR)
echo "errors=$E"
[ "$E" = 0 ] || fail "control-app ERROR=$E"
echo OP-SMOKE-PASS
