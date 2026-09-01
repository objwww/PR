#!/usr/bin/env bash
# ============================================================================
# e2e-35-real.sh —— E2E-35 真实 GitHub 非破坏性回归（docs/M2-技术方案.md §11 L6 末条）
#
# 定位：真实仓库 PR 一轮评审 + 观察 drift 巡检，验证真实模式闭环不回退。
# 本脚本只做编排与断言；不删除/编辑任何真实远端对象。
#
# 重要声明（方案原文）：E2E-26~34 中 stub 侧的删除/编辑是**合成故障演练**，
# 不等于生产平台等价验证；本条是唯一的真实平台回归面。
#
# 前置（真实模式栈，见 README「切真实 GitHub / 真实模型」）：
#   - .env：GITHUB_API_BASE=https://api.github.com（或不含 github-stub）、
#     真实 GITHUB_APP_ID / GITHUB_INSTALLATION_ID、真实 AGENT_MODEL_API_KEY；
#   - keys/github-app-key.pem 为真实 App 私钥（chmod 444）；
#   - github-stub 服务已移除（无 stub journal，断言全部走真实 API + DB）。
# 输入（环境变量）：
#   E2E35_REPO      真实仓库 owner/name（必填，如 objwww/mall_R）
#   E2E35_PR        已存在的 open PR 号（可选；缺省时由 gh-api.sh 建 draft PR，
#                   需要 E2E35_HEAD_BRANCH=<已推送的分支名>）
#   E2E35_CLOSE=1   结束时关闭脚本创建的 draft PR（默认不关，非破坏性优先）
#
# 用法：bash e2e-35-real.sh
# 证据：smoke-evidence/e2e35-<ts>/（gh-api 响应、webhook 负载、DB 摘录、summary.txt）。
# 复原：无任何破坏性动作；脚本创建的 draft PR 默认保留（E2E35_CLOSE=1 才关闭）。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"

EVIDENCE="smoke-evidence/e2e35-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$EVIDENCE"
SUMMARY="$EVIDENCE/summary.txt"; : > "$SUMMARY"
PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  [PASS] $*" | tee -a "$SUMMARY"; }
bad() { FAIL=$((FAIL + 1)); echo "  [FAIL] $*" | tee -a "$SUMMARY"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1（=$2）"; else bad "$1：实际=[$2] 期望=[$3]"; fi }

[ -f .env ] || { echo "缺 .env"; exit 1; }
set -a; . ./.env; set +a

# ---------------------------------------------------------------- preflight
echo "== E2E-35 preflight（真实模式门） ==" | tee -a "$SUMMARY"
case "${GITHUB_API_BASE:-https://api.github.com}" in
    *github-stub*) echo "当前是 stub 模式（GITHUB_API_BASE=$GITHUB_API_BASE）；E2E-35 需真实模式栈"; exit 1 ;;
esac
[ -n "${E2E35_REPO:-}" ] || { echo "缺 E2E35_REPO=owner/name"; exit 1; }
[ -f keys/github-app-key.pem ] || { echo "缺 keys/github-app-key.pem（真实 App 私钥）"; exit 1; }
ok "真实模式：GITHUB_API_BASE=${GITHUB_API_BASE:-https://api.github.com}，目标仓库 $E2E35_REPO"

psql() { docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc "$1"; }
wait_sql() { # <描述> <超时秒> <期望> <SQL>
    local desc="$1" timeout="$2" want="$3" sql="$4" got=""
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        got=$(psql "$sql" 2>/dev/null | tr -d '[:space:]')
        [ "$got" = "$want" ] && { echo "  [就绪] $desc"; return 0; }
        sleep 5
    done
    echo "  [超时] $desc（>${timeout}s，最后=[$got]）"; return 1
}

# ---------------------------------------------------------------- 目标 PR
CREATED_PR=""
if [ -n "${E2E35_PR:-}" ]; then
    PR="$E2E35_PR"
else
    [ -n "${E2E35_HEAD_BRANCH:-}" ] || { echo "缺 E2E35_PR 且缺 E2E35_HEAD_BRANCH（建 draft PR 用）"; exit 1; }
    BASE_BRANCH=$(bash gh-api.sh GET "/repos/$E2E35_REPO" | tee "$EVIDENCE/repo.json" | jq -r '.default_branch')
    PR_JSON=$(bash gh-api.sh POST "/repos/$E2E35_REPO/pulls" \
        "{\"title\":\"E2E-35 真实回归（$(date +%F %T)）\",\"head\":\"$E2E35_HEAD_BRANCH\",\"base\":\"$BASE_BRANCH\",\"draft\":true}")
    echo "$PR_JSON" > "$EVIDENCE/pr-create.json"
    PR=$(echo "$PR_JSON" | jq -r '.number')
    CREATED_PR="$PR"
    echo "  已建 draft PR #$PR（$E2E35_HEAD_BRANCH → $BASE_BRANCH）" | tee -a "$SUMMARY"
fi

PR_META=$(bash gh-api.sh GET "/repos/$E2E35_REPO/pulls/$PR")
echo "$PR_META" > "$EVIDENCE/pr-meta.json"
HEAD_SHA=$(echo "$PR_META" | jq -r '.head.sha')
BASE_SHA=$(echo "$PR_META" | jq -r '.base.sha')
REPO_ID=$(echo "$PR_META" | jq -r '.base.repo.id')
DRAFT=$(echo "$PR_META" | jq -r '.draft')
assert_eq "PR #$PR 元数据可读" "$([ -n "$HEAD_SHA" ] && [ "$HEAD_SHA" != null ] && echo yes)" "yes"
echo "  head=$HEAD_SHA base=$BASE_SHA draft=$DRAFT" | tee -a "$SUMMARY"

# ---------------------------------------------------------------- 触发一轮评审
# 真实 GitHub 无法投递到 195 的 loopback，沿用 DP-13 模式：本地合成签名 webhook
#（负载用真实 repo id / sha，HMAC 用栈自己的 GITHUB_WEBHOOK_SECRET）
PAYLOAD="$EVIDENCE/webhook.json"
cat > "$PAYLOAD" <<JSON
{"action":"synchronize","number":$PR,
 "installation":{"id":${GITHUB_INSTALLATION_ID:?}},
 "repository":{"id":$REPO_ID,"full_name":"$E2E35_REPO"},
 "pull_request":{"state":"open","draft":$DRAFT,"merged":false,
   "head":{"sha":"$HEAD_SHA"},"base":{"ref":"main","sha":"$BASE_SHA"}}}
JSON
SIG=$(openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" "$PAYLOAD" | sed 's/^.* //')
HTTP=$(curl -s -o "$EVIDENCE/webhook-response.json" -w '%{http_code}' \
    -X POST "http://127.0.0.1:${CONTROL_PORT:-8080}/webhooks/github" \
    -H "X-Hub-Signature-256: sha256=$SIG" -H "X-GitHub-Event: pull_request" \
    -H "X-GitHub-Delivery: e2e35-$(date +%s)" -H 'Content-Type: application/json' \
    --data-binary @"$PAYLOAD")
assert_eq "webhook 受理" "$HTTP" "202"

# 真实模型分钟级：宽限 20 分钟
wait_sql "outbox 两条 CONFIRMED（真实评审闭环）" 1200 "2" \
    "select count(*) from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$E2E35_REPO' and s.pr_number=$PR and oc.state='CONFIRMED'" || true

CK_OP=$(psql "select oc.operation_id from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$E2E35_REPO' and s.pr_number=$PR and oc.command_type='CREATE_CHECK' order by oc.created_at desc limit 1" | tr -d '[:space:]')
RV_OP=$(psql "select oc.operation_id from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$E2E35_REPO' and s.pr_number=$PR and oc.command_type='PUBLISH_REVIEW' order by oc.created_at desc limit 1" | tr -d '[:space:]')

# ---------------------------------------------------------------- 真实 API 断言
bash gh-api.sh GET "/repos/$E2E35_REPO/commits/$HEAD_SHA/check-runs" > "$EVIDENCE/check-runs.json" 2>&1 \
    && ok "真实 API：check-runs 列表可读" || bad "真实 API check-runs 读取失败"
N_CHECK=$(jq --arg e "$CK_OP" '[.check_runs[] | select(.external_id==$e)] | length' "$EVIDENCE/check-runs.json" 2>/dev/null || echo 0)
assert_eq "真实远端 check-run 恰 1 个（external_id=operation_id）" "$N_CHECK" "1"
bash gh-api.sh GET "/repos/$E2E35_REPO/pulls/$PR/reviews" > "$EVIDENCE/reviews.json" 2>&1 \
    && ok "真实 API：reviews 列表可读" || bad "真实 API reviews 读取失败"
N_RV=$(jq --arg m "<!-- ai-review:$RV_OP -->" '[.[] | select(.body != null and (.body | contains($m)))] | length' "$EVIDENCE/reviews.json" 2>/dev/null || echo 0)
assert_eq "真实远端 review 恰 1 条（幂等 marker）" "$N_RV" "1"

# ---------------------------------------------------------------- drift 巡检观察
# 资源行初始 next_check_at=now() → 首轮巡检在 drift idle（默认 60s）内到期；
# 真实模式下探针应找到对象：PRESENT 保持 + last_checked_at 推进 + 零 repair 单
RID=$(psql "select id from publication_resource where created_by_operation_id='$CK_OP'" | tr -d '[:space:]')
wait_sql "drift 首轮巡检完成（last_checked_at 非空）" 300 "t" \
    "select last_checked_at is not null from publication_resource where id='$RID'" || true
assert_eq "巡检后资源仍 PRESENT（真实平台无 drift 误报）" \
    "$(psql "select state from publication_resource where id='$RID'" | tr -d '[:space:]')" "PRESENT"
assert_eq "零 repair 单（真实平台无 MISSING 误判）" \
    "$(psql "select count(*) from repair_request where publication_resource_id='$RID'" | tr -d '[:space:]')" "0"

# ---------------------------------------------------------------- 复原与收尾
if [ -n "$CREATED_PR" ] && [ "${E2E35_CLOSE:-0}" = "1" ]; then
    bash gh-api.sh PATCH "/repos/$E2E35_REPO/pulls/$CREATED_PR" '{"state":"closed"}' > /dev/null 2>&1 \
        && echo "  复原：draft PR #$CREATED_PR 已关闭" | tee -a "$SUMMARY"
else
    echo "  复原：无破坏性动作${CREATED_PR:+；draft PR #$CREATED_PR 保留（E2E35_CLOSE=1 可关）}" | tee -a "$SUMMARY"
fi
echo "  声明：E2E-26~34 的 stub 删除/编辑为合成故障演练，本条为真实平台非破坏性回归" | tee -a "$SUMMARY"

echo "==================================================" | tee -a "$SUMMARY"
echo "E2E-35 结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE" | tee -a "$SUMMARY"
[ "$FAIL" -eq 0 ]
