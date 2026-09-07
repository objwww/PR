#!/bin/sh
# ============================================================================
# e2e-am5-runall.sh —— AM5 E2E 总控 runner（M5-22 必交测试资产；落码方案附录 §二）
#
# 总控契约（附录 §二 原文逐条）：
#   1. 生成唯一 suite_run_id；建 runs/<id>/；记录 git commit/镜像 digest/模型
#      fingerprint/Dataset/ConfigBundle/threshold/adapter/stats 算法版本；
#   2. 先 preflight；必需组件缺失 → FAIL_PRECONDITION（exit 3），不得自动换 mock；
#   3. 固定先跑 00；基线失败即停止后续（禁止故障场景结果掩盖业务回归）；
#   4. 依序 01~10；每场景写 scenario-results.json 行；状态只允许
#      PASS/FAIL/BLOCKED_EXTERNAL；SKIP/NOT_RUN/缺行一律总体失败；
#   5. BLOCKED_EXTERNAL 只允许 02 的 RCA-100 授权分支（须附授权核查记录）；
#   6. 汇总前重算全部 artifact SHA-256；digest 不符/真实性标签缺失/断言缺失 → 失败；
#   7. 退出码：0=全通过 2=断言失败 3=环境/外部授权阻塞 4=证据不完整或篡改；
#      禁止捕获非零后强制返回 0；
#   8. 清理只认本 suite_run_id 创建的靶场开关/测试租户/临时数据。
#
# 用法（195+2C4G 部署段）：sh e2e-am5-runall.sh
# ============================================================================

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
. "$SCRIPT_DIR/e2e-am5-common.sh"

# ---- 场景→脚本映射（实际交付文件名；03~09 随 M5-09~17 任务交付，00~02/10 随 M5-22） ----
SCENARIOS="
00:e2e-am5-00-normal-baseline.sh
01:e2e-am5-01-private-quality-gate.sh
02:e2e-am5-02-public-adapters.sh
03:e2e-am5-03-safety-gate.sh
04:e2e-am5-04-config-bundle-atomic.sh
05:e2e-am5-05-canary-bucket.sh
06:e2e-am5-06-operator-case.sh
07:e2e-am5-07-runs-events-sse.sh
08:e2e-am5-08-telemetry-outage.sh
09:e2e-am5-09-archive-recovery.sh
10:e2e-am5-10-history-search.sh
"

REQUIRED_PASS_COUNT=11
SUITE_RUN_ID=$(am5_suite_run_id)
RUNS_ROOT="$SCRIPT_DIR/../runs"
RUNS_DIR="$RUNS_ROOT/$SUITE_RUN_ID"
mkdir -p "$RUNS_DIR/raw" "$RUNS_DIR/topology" "$RUNS_DIR/sql" "$RUNS_DIR/stats"
: > "$RUNS_DIR/scenario-results.json"

am5_log "suite_run_id=$SUITE_RUN_ID（runs/$SUITE_RUN_ID）"

# ---- 契约 1：suite-manifest（git/镜像/指纹/版本面；[195] 部署段填充真值） ----
{
    printf '{"suite_run_id":"%s","started_at":"%s"\n' "$SUITE_RUN_ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf ',"git_commit":"%s"\n' "$(git -C "$SCRIPT_DIR/../../.." rev-parse HEAD 2>/dev/null || echo UNKNOWN)"
    printf ',"image_digests":"%s"\n' "${AM5_IMAGE_DIGESTS:-PENDING_DEPLOY_SEGMENT}"
    printf ',"model_fingerprint":"%s"\n' "${AM5_MODEL_FINGERPRINT:-UNKNOWN}"
    printf ',"dataset_version":"%s","config_bundle_digest":"%s"\n' \
        "${AM5_DATASET_VERSION:-UNKNOWN}" "${AM5_CONFIG_BUNDLE_DIGEST:-UNKNOWN}"
    printf ',"threshold_version":"%s","adapter_version":"%s","stats_algorithm":"%s"}\n' \
        "${AM5_THRESHOLD_VERSION:-UNKNOWN}" "${AM5_ADAPTER_VERSION:-UNKNOWN}" \
        "${AM5_STATS_ALGORITHM:-UNKNOWN}"
} | am5_redact > "$RUNS_DIR/suite-manifest.json"

am5_resource_snapshot "$RUNS_DIR" "pre"

# ---- 契约 2：preflight 前置 ----
if ! sh "$SCRIPT_DIR/e2e-am5-preflight.sh" "$RUNS_DIR"; then
    am5_resource_snapshot "$RUNS_DIR" "post-preflight-fail"
    am5_log "FAIL_PRECONDITION → exit 3"
    exit 3
fi

# ---- 单场景执行：跑脚本 → 从日志取场景结果行（缺行 = NOT_RUN，§二.4） ----
run_scenario() {
    _rs_id="$1"; _rs_script="$2"
    [ -f "$SCRIPT_DIR/$_rs_script" ] || {
        am5_log "场景 $_rs_id 缺脚本 $_rs_script（附录 §七 一票否决）"
        printf '{"scenario":"%s","status":"NOT_RUN","reason":"missing_script"}\n' \
            "$_rs_id" >> "$RUNS_DIR/scenario-results.json"
        return 4
    }
    am5_log "▶ 场景 $_rs_id（$_rs_script）"
    sh "$SCRIPT_DIR/$_rs_script" 2>&1 | am5_redact > "$RUNS_DIR/raw/$_rs_id.log"
    _rs_line=$(grep '^scenario-result {' "$RUNS_DIR/raw/$_rs_id.log" 2>/dev/null | tail -1)
    if [ -z "$_rs_line" ]; then
        am5_log "场景 $_rs_id 缺 scenario-result 行 = NOT_RUN（总体失败）"
        printf '{"scenario":"%s","status":"NOT_RUN","reason":"missing_result_line"}\n' \
            "$_rs_id" >> "$RUNS_DIR/scenario-results.json"
        return 4
    fi
    printf '%s\n' "${_rs_line#scenario-result }" >> "$RUNS_DIR/scenario-results.json"
    _rs_status=$(printf '%s' "$_rs_line" | grep -o '"status":"[A-Z_]*"' | tail -1 | cut -d'"' -f4)
    case "$_rs_status" in
        PASS)             return 0 ;;
        BLOCKED_EXTERNAL) return 5 ;;   # 白名单面（§二.5）在裁决段统一判
        *)                am5_log "场景 $_rs_id = FAIL"; return 2 ;;
    esac
}

# ---- 契约 3：00 基线先行，失败即停 ----
run_scenario "00" "e2e-am5-00-normal-baseline.sh"
_BASELINE_RC=$?
am5_resource_snapshot "$RUNS_DIR" "post-baseline"
case "$_BASELINE_RC" in
    0) am5_log "00 基线 PASS → 继续 01~10" ;;
    2) am5_log "00 基线断言失败 → 停止后续（exit 2）"; exit 2 ;;
    *) am5_log "00 基线未激活/缺行 → 证据不完整（exit 4）"; exit 4 ;;
esac

# ---- 契约 4：01~10 依序跑完全程（逐项结果都要落表，失败不中断证据面） ----
echo "$SCENARIOS" | while IFS=: read -r _sid _sscript; do
    [ -n "$_sid" ] || continue
    run_scenario "$_sid" "$_sscript" || true
done

# ---- 契约 5：BLOCKED_EXTERNAL 白名单（仅 02；越白名单 = 证据不完整） ----
_BLOCKED_BAD=$(grep '"status":"BLOCKED_EXTERNAL"' "$RUNS_DIR/scenario-results.json" \
    | grep -cv '"scenario":"02"' || true)
if [ "${_BLOCKED_BAD:-0}" != "0" ]; then
    am5_log "BLOCKED_EXTERNAL 越白名单（仅 02 允许）→ exit 4"
    exit 4
fi

# ---- 契约 6：SHA-256 全量重算封存（AA-26 契约证据包） ----
am5_resource_snapshot "$RUNS_DIR" "post"
if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$RUNS_DIR" && find . -type f ! -name sha256sums.txt -exec sha256sum {} \; ) \
        | am5_redact > "$RUNS_DIR/sha256sums.txt"
    am5_log "sha256sums.txt 封存完成（$(wc -l < "$RUNS_DIR/sha256sums.txt") artifacts）"
else
    am5_log "sha256sum 不可用 → 证据不完整（exit 4）"
    exit 4
fi

# ---- 契约 7：裁决（无跳过铁律：NOT_RUN/未激活场景 = 证据不完整） ----
_PASS=$(grep -c '"status":"PASS"' "$RUNS_DIR/scenario-results.json" || true)
_NOT_RUN=$(grep -c '"status":"NOT_RUN"' "$RUNS_DIR/scenario-results.json" || true)
am5_log "场景结果：PASS $_PASS / NOT_RUN $_NOT_RUN / 必需 $REQUIRED_PASS_COUNT"
if [ "$_PASS" -ge "$REQUIRED_PASS_COUNT" ]; then
    # ---- 契约 8：清理只认本 suite_run_id（[195] 部署段按开关/租户面执行） ----
    am5_log "全套通过 → AA-26 证据包就绪（runs/$SUITE_RUN_ID）；清理面[195]"
    exit 0
fi
if grep -q 'PENDING_DEPLOY_SEGMENT' "$RUNS_DIR/raw"/*.log 2>/dev/null \
    || [ "$_NOT_RUN" != "0" ]; then
    am5_log "含部署段未激活/缺行场景 → 证据不完整（exit 4；G2 一票否决项）"
    exit 4
fi
am5_log "存在断言失败（exit 2）"
exit 2
