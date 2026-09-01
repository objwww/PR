#!/usr/bin/env bash
# ============================================================================
# bt-m2.sh —— M2 L5 业务验收演示（BT：G2 演示脚本，用户视角，五要素；
# docs/M2-技术方案.md §11 L5 表）
#
# 用法：bash bt-m2.sh <BT-M2-01 | BT-M2-02 | BT-M2-03 | all>
# 演示基调：每条用例打印"用户视角叙事行"（[演示]），断言行与 DP/E2E 同风格。
# 证据：smoke-evidence/bt-<ts>/<case>/；复原：trap m2_cleanup EXIT。
#
# 前置：stub GitHub 模式；BT-M2-01 另需 stub 模型模式（模型计数经 stub journal）。
# 加速旋钮见 e2e-m2.sh 头注（BT-01 的"秒级完成"以租约快速回收为前提：
# 默认 app.worker.max-lease-seconds=600s 下，恢复耗时由租约过期主导——脚本对
# 耗时只展示不硬断言，硬断言是"模型计数恰 1"；配 APP_WORKER_MAXLEASESECONDS=60
# 时附加耗时门禁）。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"

CASE="${1:-}"
[ -n "$CASE" ] || { echo "用法：bash bt-m2.sh <BT-M2-01|BT-M2-02|BT-M2-03|all>" >&2; exit 2; }

[ -f .env ] || { echo "缺 .env（见 README）"; exit 1; }
set -a; . ./.env; set +a

EVIDENCE="smoke-evidence/bt-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$EVIDENCE"
export M2_EVIDENCE="$EVIDENCE"
. ./m2-lib.sh
trap m2_cleanup EXIT
m2_probe_sync_start   # TB-13：stub 探针联动守护

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  [PASS] $*" | tee -a "$SUMMARY"; }
bad() { FAIL=$((FAIL + 1)); echo "  [FAIL] $*" | tee -a "$SUMMARY"; }
note() { echo "  [NOTE] $*" | tee -a "$SUMMARY"; }
demo() { echo "  [演示] $*" | tee -a "$SUMMARY"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1（=$2）"; else bad "$1：实际=[$2] 期望=[$3]"; fi }

begin_case() {
    CASE_DIR="$EVIDENCE/$1"; mkdir -p "$CASE_DIR"
    M2_EVIDENCE="$CASE_DIR"; SUMMARY="$CASE_DIR/summary.txt"; : > "$SUMMARY"
    echo "== $1 $2 ==" | tee -a "$SUMMARY"
}
end_case() { echo "-- $1 累计：PASS=$PASS FAIL=$FAIL" | tee -a "$SUMMARY"; }

m2_stub_github_mode || { echo "BT 需 stub GitHub 模式（GITHUB_API_BASE 指 github-stub）"; exit 1; }

# ---------------------------------------------------------------- BT-M2-01
# 注入：stub 模型延迟 30s 放大效果；评审中 kill control
# 断言：重启后秒级完成、模型计数恰 1；取证：stub journal + step_checkpoint；复原：栈自愈
bt_01() {
    begin_case BT-M2-01 "崩溃重跑不重复烧模型费（checkpoint 续跑）"
    m2_journal_reset
    demo "注入：stub 模型延迟 30s（放大评审窗口，等效真实模型分钟级调用）"
    m2_model_delay_on 30000
    local PR=$((31000 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" bt01)
    assert_eq "webhook 受理" "$HTTP" "202"
    demo "评审进行中……（等待模型返回 + checkpoint 落库）"
    m2_wait_sql "checkpoint 落库（模型产出已可跨崩溃找回）" 300 "1" \
        "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR" || true
    demo "用户操作：kill -9 control（模拟宿主机宕机/OOM）"
    local ST; ST=$(m2_kill_app control-app)
    [ "$ST" != "running" ] && ok "control 已 SIGKILL（State=$ST）" || bad "control 仍在运行（$ST）"
    m2_model_delay_off
    docker compose up -d control-app > /dev/null 2>&1
    m2_wait_for "control 复活 401" 180 m2_control_alive || true
    local T_UP=$SECONDS
    demo "栈自愈：等待续跑收敛（租约回收 + checkpoint 复用 + 发布）"
    m2_wait_sql "PR#$PR outbox CONFIRMED=2" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    local ELAPSED=$((SECONDS - T_UP))
    demo "从 control 复活到评审发布完成耗时 ${ELAPSED}s"
    assert_eq "模型计数恰 1（崩溃零重调，费用不重烧）" "$(m2_model_calls "$CASE_DIR/model.json")" "1"
    assert_eq "checkpoint 恰 1 行" "$(m2_pr_checkpoint_count "$PR")" "1"
    if [ "${APP_WORKER_MAXLEASESECONDS:-600}" -le 60 ] 2>/dev/null; then
        [ "$ELAPSED" -le 180 ] && ok "续跑收敛耗时 ${ELAPSED}s ≤180s（租约加速配置下秒级~分钟级演示成立）" \
            || bad "续跑收敛耗时 ${ELAPSED}s 超阈值"
    else
        note "默认租约 600s：恢复耗时由租约过期主导（本次 ${ELAPSED}s）；配 APP_WORKER_MAXLEASESECONDS=60 可演示秒级收敛，硬断言保持不变（模型计数恰 1）"
    fi
    m2_register_pr_resources "$PR" || true
    end_case BT-M2-01
}

# ---------------------------------------------------------------- BT-M2-02
# 注入：stub 删掉 check-run；断言：下一轮巡检自动重建 + 资源历史可溯
#（旧 REPAIRED + 新 PRESENT + repaired_by 链）；取证：publication_resource 行链
# + repair_request；复原：stub 复位
bt_02() {
    begin_case BT-M2-02 "远端对象被删 → 巡检自动重建（drift 修复闭环）"
    m2_journal_reset
    local PR=$((31100 + RANDOM % 90))
    if m2_run_pr_e2e "$PR" bt02 600; then ok "基线评审闭环（check-run 已发布）"; else bad "基线未收敛"; fi
    local CK RID
    CK=$(m2_pr_op "$PR" CREATE_CHECK CONFIRMED)
    RID=$(m2_resource_of_op "$CK")
    demo "注入：stub 侧删掉 check-run（等效运维/用户在 GitHub 上误删状态检查）"
    local NEWID=$((7950000 + RANDOM % 40000))
    m2_check_present_remove "$CK"
    m2_post_check_override_on "$NEWID"
    m2_force_drift_due "$RID"
    demo "等待下一轮巡检……（DriftReconciler 发现 MISSING → 铸 AUTO 修复单 → RepairPlanner 铸命令 → 重建）"
    m2_wait_sql "巡检发现 MISSING" 240 "MISSING" \
        "select state from publication_resource where id='$RID'" || true
    m2_wait_sql "修复单铸造" 120 "1" \
        "select count(*) from repair_request where publication_resource_id='$RID'" || true
    local REQ; REQ=$(m2_request_of_resource "$RID")
    m2_wait_sql "修复闭环 REPAIRED" 300 "REPAIRED" \
        "select state from repair_request where id='$REQ'" || true
    local ROP NEWRID
    ROP=$(m2_request_field "$REQ" repair_operation_id)
    NEWRID=$(m2_psql "select id from publication_resource where replaces_resource_id='$RID' and state='PRESENT'" | tr -d '[:space:]')
    demo "资源历史可溯：旧行 REPAIRED（保留原 remote_id）→ 新行 PRESENT（replaces_resource_id 链回）"
    m2_psql "select id, resource_type, remote_id, state, repaired_by_operation_id, replaces_resource_id from publication_resource where id in ('$RID'${NEWRID:+,'$NEWRID'}) order by created_at" \
        | tee "$CASE_DIR/resource-chain.txt"
    assert_eq "旧行 REPAIRED" "$(m2_resource_field "$RID" state)" "REPAIRED"
    assert_eq "旧行 repaired_by=repair 命令" "$(m2_resource_field "$RID" repaired_by_operation_id)" "$ROP"
    [ -n "$NEWRID" ] && ok "新 PRESENT 行链回旧行" || bad "缺新 PRESENT 行"
    assert_eq "新行 remote_id=重建的新对象" "$(m2_resource_field "$NEWRID" remote_id)" "$NEWID"
    m2_post_check_override_off
    [ -n "$ROP" ] && m2_check_present_add "$ROP" "$NEWID"
    demo "复原：stub 运行时映射已复位（trap 兜底）"
    end_case BT-M2-02
}

# ---------------------------------------------------------------- BT-M2-03
# 注入：stub 编辑 review 评论正文（marker 保留）；断言：CONTENT_DRIFTED 告警出现
# 且不自动改回；取证：execution_event + resource 仍 PRESENT；复原：stub 复位
bt_03() {
    begin_case BT-M2-03 "review 评论被篡改 → 告警但不自动改写（R2）"
    m2_journal_reset
    local PR=$((31200 + RANDOM % 90))
    if m2_run_pr_e2e "$PR" bt03 600; then ok "基线评审闭环（review 已发布）"; else bad "基线未收敛"; fi
    local RVOP RVID
    RVOP=$(m2_pr_op "$PR" PUBLISH_REVIEW CONFIRMED)
    RVID=$(m2_resource_of_op "$RVOP")
    local REMOTE; REMOTE=$(m2_resource_field "$RVID" remote_id)
    # 原正文（含隐藏 marker）从 stub journal 取回
    local BODY0
    BODY0=$(jq -r '.requests[-1].body | fromjson | .body' "$CASE_DIR/m2-register-$PR-reviews.json" 2>/dev/null)
    if [ -z "$BODY0" ] || [ "$BODY0" = "null" ]; then
        m2_journal_find POST "\"url\":\"/repos/${M2_REPO}/pulls/$PR/reviews\"" "$CASE_DIR/reviews.json"
        BODY0=$(jq -r '.requests[-1].body | fromjson | .body' "$CASE_DIR/reviews.json" 2>/dev/null)
    fi
    [ -n "$BODY0" ] && [ "$BODY0" != "null" ] || { bad "取不到原 review 正文（journal）"; end_case BT-M2-03; return; }
    demo "注入：stub 侧编辑 review 正文（内容被改、marker 保留——等效有人编辑了评论）"
    local BODY1="${BODY0}

（stub 合成故障：本行是注入的篡改内容）"
    m2_review_present_set "$PR" "$REMOTE" "$BODY1"
    m2_force_drift_due "$RVID"
    demo "等待下一轮巡检……（digest 比对发现内容不符 → episode 告警，不自动改回）"
    m2_wait_sql "PUBLICATION_CONTENT_DRIFTED 告警" 240 "1" \
        "select count(*) from execution_event ee join review_run rr on rr.id=ee.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR and ee.event_type='PUBLICATION_CONTENT_DRIFTED'" || true
    assert_eq "资源仍 PRESENT（不冒充 MISSING、不铸单）" "$(m2_resource_field "$RVID" state)" "PRESENT"
    local DGST
    DGST=$(printf '%s' "$BODY1" | openssl dgst -sha256 | sed 's/^.* //')
    assert_eq "content_drift_digest=篡改后正文 sha256（episode 键）" \
        "$(m2_resource_field "$RVID" content_drift_digest)" "$DGST"
    [ -n "$(m2_resource_field "$RVID" content_drift_detected_at)" ] \
        && ok "content_drift_detected_at 已记录" || bad "content_drift_detected_at 为空"
    m2_journal_find POST "\"url\":\"/repos/${M2_REPO}/pulls/$PR/reviews\"" "$CASE_DIR/reviews-after.json"
    assert_eq "review 零自动重发（POST 仍恰 1 次）" "$(m2_journal_count "$CASE_DIR/reviews-after.json")" "1"
    m2_journal_find PATCH '"urlPathPattern":"/repos/[^/]+/[^/]+/pulls/[0-9]+/reviews/[0-9]+"' "$CASE_DIR/reviews-patch.json"
    assert_eq "review 零自动改写（PATCH 0 次）" "$(m2_journal_count "$CASE_DIR/reviews-patch.json")" "0"
    assert_eq "零 repair 单（内容漂移只告警）" \
        "$(m2_psql "select count(*) from repair_request where publication_resource_id='$RVID'" | tr -d '[:space:]')" "0"
    demo "复原：stub 侧恢复原文（episode 应关闭：digest 置 NULL、不重复告警）"
    m2_review_present_set "$PR" "$REMOTE" "$BODY0"
    m2_force_drift_due "$RVID"
    m2_wait_sql "episode 关闭（content_drift_digest 置 NULL）" 240 "" \
        "select coalesce(content_drift_digest::text,'') from publication_resource where id='$RVID'" \
        && ok "恢复原文后 episode 关闭" \
        || note "episode 关闭未在窗口内观察到（不影响告警断言；下一轮巡检收敛）"
    end_case BT-M2-03
}

case "$CASE" in
    BT-M2-01)
        m2_stub_model_mode || { echo "BT-M2-01 需 stub 模型模式（模型计数经 stub journal 取证）"; exit 1; }
        bt_01 ;;
    BT-M2-02) bt_02 ;;
    BT-M2-03) bt_03 ;;
    all)
        if m2_stub_model_mode; then bt_01; else echo "[SKIP] BT-M2-01（非 stub 模型模式）"; fi
        bt_02; bt_03 ;;
    *) echo "未知用例 $CASE" >&2; exit 2 ;;
esac

echo "=================================================="
echo "BT 结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE"
[ "$FAIL" -eq 0 ]
