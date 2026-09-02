#!/usr/bin/env bash
# ============================================================================
# e2e-m2.sh —— M2 L6 整栈故障注入（compose 真栈，195；docs/M2-技术方案.md §11 L6 表）
#
# 用法：
#   bash e2e-m2.sh E2E-26 W1        # 单窗口（W1/W4/W5；W2/W3 见下诚实清单）
#   bash e2e-m2.sh E2E-26 all       # 五窗口（W2/W3 记 NOTE）
#   bash e2e-m2.sh E2E-27           # 单用例
#   bash e2e-m2.sh all              # E2E-26~34 全量（E2E-35 真实 GitHub 单列，
#                                   # 见 e2e-35-real.sh）
# 证据：smoke-evidence/e2e-<ts>/<case>/（journal 快照 + SQL 摘录 + summary.txt）。
# 复原：trap m2_cleanup EXIT 摘除全部 stub 运行时映射、unpause 容器、删 e2e27-control2；
#   各用例内另有就地复原（故障映射摘除、探针映射回填）。compose down/up 用例（E2E-30）
#   后 stub 运行时映射天然清空，用例内重建所需映射。
#
# 前置：deploy/.env 已接入；stub GitHub 模式（GITHUB_API_BASE 指 github-stub）；
#   E2E-26/27/30 另需 stub 模型模式（OPENAI_COMPAT_BASE_URL 指 github-stub，
#   模型调用计数经 stub journal 取证）。
#
# 加速旋钮（.env 可选，compose 已透传；默认与代码默认一致）：
#   APP_WORKER_MAXLEASESECONDS=60     # work_item 租约（默认 600s，SIGKILL 后回收等待）
#   PUBLISHER_LEASESECONDS=30         # outbox 租约（默认 60s，publisher SIGKILL 后回收）
#   PUBLISHER_DRIFT_IDLESLEEPMS=10000 # drift 巡检循环空闲间隔（默认 60s）
#
# 诚实清单（无法纯脚本自动化的环节，执行方须知）：
#   1) E2E-26 W2（双 CAS 中间崩溃）/W3（checkpoint 事务前崩溃）：进程内微窗口，
#      栈外无注入点（需容器内故障点）。由 ST-25/ST-26 IT 钉死；本脚本记 [NOTE] 不伪造。
#      W1（模型调用中，CAS 前）经 stub 模型延迟放大窗口可真实注入；W4/W5 轮询 DB 命中。
#   2) W4「checkpoint 后、T2 前」与 DP-17 同为毫秒级窗口：轮询 checkpoint 落库后
#      立即 SIGKILL，实际落点可能是「T2 后」（W5）；两窗口期望同为模型计数恰 1，
#      门禁语义等价；精确「T2 未完」由 ST-27 IT 覆盖。
#   3) E2E-27 的「旧 Worker 晚到写」用 docker pause 冻结旧 Worker 模拟僵尸（进程
#      存活但停止 heartbeat），由第二 control 容器（compose run，不发布端口）接管；
#      租约过期时长决定用例耗时（默认 600s，建议加速旋钮）。
#   4) E2E-30A「checkpoint 已提交 T2 未完」：compose down 本身的秒级耗时使实际落点
#      常为「T2 后」；断言（恢复零模型重调、终态正确）对两落点等价成立。
#   5) E2E-34 恢复段（EX-28 v1.2 裁定：人工复位制）：UNKNOWN 不在巡检扫描集，权限恢复
#      后不自动复归为既定行为（非 TB）；脚本确认不复归 → 执行 runbook 复位 SQL → 断言复归。
#   6) stub 的删除/编辑均为合成故障演练（运行时映射注入），不等于生产平台等价
#      验证——真实平台回归见 E2E-35（e2e-35-real.sh）。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"

CASE="${1:-}"
SUB="${2:-all}"
usage() {
    echo "用法：bash e2e-m2.sh <E2E-26 [W1|W4|W5|all] | E2E-27 | ... | E2E-34 | all>" >&2
    exit 2
}
[ -n "$CASE" ] || usage

[ -f .env ] || { echo "缺 .env（见 README）"; exit 1; }
set -a; . ./.env; set +a

EVIDENCE="smoke-evidence/e2e-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$EVIDENCE"
export M2_EVIDENCE="$EVIDENCE"
. ./m2-lib.sh
trap m2_cleanup EXIT
m2_probe_sync_start   # TB-13：stub 探针联动守护（重建即可见，防 drift-repair 风暴）

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  [PASS] $*" | tee -a "$SUMMARY"; }
bad() { FAIL=$((FAIL + 1)); echo "  [FAIL] $*" | tee -a "$SUMMARY"; }
note() { echo "  [NOTE] $*" | tee -a "$SUMMARY"; }
skip() { echo "  [SKIP] $*" | tee -a "$SUMMARY"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1（=$2）"; else bad "$1：实际=[$2] 期望=[$3]"; fi }

# work_item join 片段（PR 圈定）
wi_where() { echo "from work_item wi join run_step rs on rs.id=wi.step_id join review_run rr on rr.id=wi.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1"; }

begin_case() { # <case-id> <标题>：每用例独立证据子目录与 summary
    CASE_DIR="$EVIDENCE/$1"
    mkdir -p "$CASE_DIR"
    M2_EVIDENCE="$CASE_DIR"
    SUMMARY="$CASE_DIR/summary.txt"
    : > "$SUMMARY"
    echo "== $1 $2 ==" | tee -a "$SUMMARY"
}

end_case() { echo "-- $1 累计：PASS=$PASS FAIL=$FAIL" | tee -a "$SUMMARY"; }

# ---------------------------------------------------------------- E2E-26
# 参数化 SIGKILL 五窗口；模型计数期望 W1/W2/W3=2、W4/W5=1
e26_w1() { # 窗口① CAS 前（模型调用中）：无 checkpoint → 重调模型
    begin_case E2E-26-W1 "SIGKILL 窗口：CAS 前（模型调用中）"
    m2_journal_reset
    m2_model_delay_on 45000
    local PR=$((20000 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e26w1)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_journal "模型调用已发出（窗口开启）" 120 POST ".*/chat/completions" 1 || true
    local ST; ST=$(m2_kill_app control-app)
    [ "$ST" != "running" ] && ok "control 已 SIGKILL（State=$ST）" || bad "control 仍在运行（$ST）"
    m2_model_delay_off
    docker compose up -d control-app > /dev/null 2>&1
    m2_wait_for "control 复活 401" 180 m2_control_alive || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "模型计数=2（无 checkpoint 可复用，重调一次）" "$(m2_model_calls "$CASE_DIR/model.json")" "2"
    assert_eq "checkpoint 恰 1 行（attempt2 写入）" "$(m2_pr_checkpoint_count "$PR")" "1"
    assert_eq "finding 恰 1 条（无重复）" "$(m2_pr_finding_count "$PR")" "1"
    m2_register_pr_resources "$PR" || true
    end_case E2E-26-W1
}

e26_w4() { # 窗口④ checkpoint 后（实际落点或为 T2 后，见诚实清单 2）
    begin_case E2E-26-W4 "SIGKILL 窗口：checkpoint 提交后"
    m2_journal_reset
    m2_model_delay_on 45000
    local PR=$((20100 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e26w4)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "checkpoint 落库" 300 "1" \
        "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR" || true
    local ST; ST=$(m2_kill_app control-app)
    [ "$ST" != "running" ] && ok "control 已 SIGKILL（State=$ST）" || bad "control 仍在运行（$ST）"
    m2_model_delay_off
    docker compose up -d control-app > /dev/null 2>&1
    m2_wait_for "control 复活 401" 180 m2_control_alive || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（续跑收敛）" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "模型计数=1（checkpoint 续跑零重调）" "$(m2_model_calls "$CASE_DIR/model.json")" "1"
    assert_eq "checkpoint 恰 1 行" "$(m2_pr_checkpoint_count "$PR")" "1"
    assert_eq "finding 恰 1 条" "$(m2_pr_finding_count "$PR")" "1"
    m2_register_pr_resources "$PR" || true
    end_case E2E-26-W4
}

e26_w5() { # 窗口⑤ T2 后（outbox 已铸）：重放幂等
    begin_case E2E-26-W5 "SIGKILL 窗口：T2 提交后"
    m2_journal_reset
    local PR=$((20200 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e26w5)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "outbox 两条已铸（T2 提交）" 300 "2" \
        "select count(*) $(m2_pr_sql_where "$PR")" || true
    local ST; ST=$(m2_kill_app control-app)
    [ "$ST" != "running" ] && ok "control 已 SIGKILL（State=$ST）" || bad "control 仍在运行（$ST）"
    docker compose up -d control-app > /dev/null 2>&1
    m2_wait_for "control 复活 401" 180 m2_control_alive || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "模型计数=1（T2 后崩溃零重跑）" "$(m2_model_calls "$CASE_DIR/model.json")" "1"
    assert_eq "outbox 总数恰 2（重放不重复铸命令）" \
        "$(m2_psql "select count(*) $(m2_pr_sql_where "$PR")" | tr -d '[:space:]')" "2"
    assert_eq "finding 恰 1 条" "$(m2_pr_finding_count "$PR")" "1"
    m2_register_pr_resources "$PR" || true
    end_case E2E-26-W5
}

e2e_26() {
    case "$SUB" in
        W1) e26_w1 ;;
        W4) e26_w4 ;;
        W5) e26_w5 ;;
        all)
            e26_w1; e26_w4; e26_w5
            begin_case E2E-26-W23 "诚实清单"
            note "W2（双 CAS 中间崩溃）/W3（checkpoint 事务前崩溃）为进程内微窗口，栈外无注入点；由 ST-25/ST-26 IT 覆盖（期望模型计数 2），本脚本不伪造自动化"
            end_case E2E-26-W23
            ;;
        *) usage ;;
    esac
}

# ---------------------------------------------------------------- E2E-27
# 双 Worker 租约接管 + 旧 Worker 晚到写（I25 lease_epoch 栅栏）
e2e_27() {
    begin_case E2E-27 "双 Worker 租约接管 + 旧 Worker 晚到写"
    docker rm -f e2e27-control2 > /dev/null 2>&1 || true
    m2_journal_reset
    m2_model_delay_on 60000
    local PR=$((21000 + RANDOM % 500))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e27)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "work_item LEASED（attempt1 持有租约）" 240 "1" \
        "select count(*) $(wi_where "$PR") and wi.state='LEASED'" || true
    local EPOCH0
    EPOCH0=$(m2_psql "select lease_epoch $(wi_where "$PR")" | tr -d '[:space:]')
    m2_wait_journal "attempt1 模型调用已发出" 120 POST ".*/chat/completions" 1 || true
    # 冻结旧 Worker（僵尸：进程活着、heartbeat 停止）；第二 control 容器等租约过期接管
    docker pause "$(docker compose ps -q control-app)" > /dev/null 2>&1
    docker compose run -d -T --no-deps --name e2e27-control2 control-app > /dev/null 2>&1
    m2_model_delay_off   # 新 Worker 的模型调用不再延迟（旧请求已在 stub 侧排队）
    m2_wait_sql "租约接管（lease_epoch 递增）" 900 "t" \
        "select lease_epoch > $EPOCH0 $(wi_where "$PR")" || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（新 attempt 完成）" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    # 旧 Worker 醒来：晚到写应被 lease_epoch 栅栏挡（0 行）
    docker unpause "$(docker compose ps -q control-app)" > /dev/null 2>&1
    sleep 30
    assert_eq "checkpoint 恰 1 行（晚到写未叠加）" "$(m2_pr_checkpoint_count "$PR")" "1"
    local CK_EPOCH WI_EPOCH
    CK_EPOCH=$(m2_psql "select sc.lease_epoch from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR" | tr -d '[:space:]')
    WI_EPOCH=$(m2_psql "select lease_epoch $(wi_where "$PR")" | tr -d '[:space:]')
    assert_eq "checkpoint.lease_epoch == work_item 当前 epoch（栅栏生效）" "$CK_EPOCH" "$WI_EPOCH"
    assert_eq "SUCCEEDED attempt 恰 1（旧 attempt 不得成功收尾）" \
        "$(m2_psql "select count(*) from step_attempt sa join run_step rs on rs.id=sa.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR and sa.status='SUCCEEDED'" | tr -d '[:space:]')" "1"
    assert_eq "finding 恰 1 条（晚到写未重复落）" "$(m2_pr_finding_count "$PR")" "1"
    docker rm -f e2e27-control2 > /dev/null 2>&1
    echo "  复原：e2e27-control2 已删、control-app 已 unpause" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-27
}

# ---------------------------------------------------------------- E2E-28
# stub 删 check-run + 等巡检周期 → 自动修复闭环（MISSING→单→命令→新 PRESENT）
# 返回码之外给 E2E-33 复用的出口变量：ROUND_REQ/ROUND_ROP/ROUND_NEW_RID
drift_repair_round() { # <pr> <ck_op> <rid> <new_remote_id> <标签>
    local PR="$1" CK="$2" RID="$3" NEWID="$4" TAG="$5"
    m2_check_present_remove "$CK"
    m2_post_check_override_on "$NEWID"
    m2_force_drift_due "$RID"
    m2_wait_sql "[$TAG] 资源 MISSING" 240 "MISSING" \
        "select state from publication_resource where id='$RID'" || true
    m2_wait_sql "[$TAG] repair_request 铸造" 120 "1" \
        "select count(*) from repair_request where publication_resource_id='$RID'" || true
    ROUND_REQ=$(m2_request_of_resource "$RID")
    assert_eq "[$TAG] 档级 AUTO" "$(m2_request_field "$ROUND_REQ" policy_tier)" "AUTO"
    m2_wait_sql "[$TAG] 修复单 REPAIRED" 300 "REPAIRED" \
        "select state from repair_request where id='$ROUND_REQ'" || true
    ROUND_ROP=$(m2_request_field "$ROUND_REQ" repair_operation_id)
    assert_eq "[$TAG] 旧行 REPAIRED" "$(m2_resource_field "$RID" state)" "REPAIRED"
    assert_eq "[$TAG] 旧行 repaired_by=repair 命令" "$(m2_resource_field "$RID" repaired_by_operation_id)" "$ROUND_ROP"
    ROUND_NEW_RID=$(m2_psql "select id from publication_resource where replaces_resource_id='$RID' and state='PRESENT'" | tr -d '[:space:]')
    [ -n "$ROUND_NEW_RID" ] && ok "[$TAG] 新 PRESENT 行链回旧行" || bad "[$TAG] 缺新 PRESENT 行"
    assert_eq "[$TAG] 新行 remote_id=新远端对象" "$(m2_resource_field "$ROUND_NEW_RID" remote_id)" "$NEWID"
    m2_post_check_override_off
    [ -n "$ROUND_ROP" ] && m2_check_present_add "$ROUND_ROP" "$NEWID"
}

e2e_28() {
    begin_case E2E-28 "stub 删 check-run + 等巡检 → 自动修复闭环"
    m2_journal_reset
    local PR=$((22000 + RANDOM % 500))
    if m2_run_pr_e2e "$PR" e28 600; then ok "基线闭环 CONFIRMED=2"; else bad "基线闭环未收敛"; fi
    local CK RID NEWID=$((7500000 + RANDOM % 50000))
    CK=$(m2_pr_op "$PR" CREATE_CHECK CONFIRMED)
    RID=$(m2_resource_of_op "$CK")
    drift_repair_round "$PR" "$CK" "$RID" "$NEWID" "R1"
    assert_eq "REPAIR Run 独立铸造（I27）" \
        "$(m2_psql "select run_mode from review_run rr join repair_request q on q.repair_run_id=rr.id where q.id='$ROUND_REQ'" | tr -d '[:space:]')" "REPAIR"
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks.json"
    assert_eq "stub check-runs POST 恰 2 次（原始+重建）" "$(m2_journal_count "$CASE_DIR/stub-checks.json")" "2"
    end_case E2E-28
}

# ---------------------------------------------------------------- E2E-29
# publisher 远端写成功、本地 CONFIRM 前 SIGKILL → 恢复后 reconcile 探针认领，
# 远端恰好一个对象（A-2）
e2e_29() {
    begin_case E2E-29 "写已达远端、CONFIRM 前 SIGKILL → reconcile 认领"
    m2_journal_reset
    # TB-12：延迟响应 id 与探针预注册 id 同源唯一（两子情形——响应已收/未收——注册同一 id）
    local E29ID=$((7150000 + RANDOM % 50000))
    m2_post_check_delay_on 15000 "$E29ID"   # 放大"写已达、CONFIRM 前"窗口
    local PR=$((23000 + RANDOM % 500))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e29)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "CREATE_CHECK 已铸（T2 完成）" 300 "t" \
        "select count(*) >= 1 $(m2_pr_sql_where "$PR") and oc.command_type='CREATE_CHECK'" || true
    local CK; CK=$(m2_pr_op "$PR" CREATE_CHECK)
    m2_check_present_add "$CK" "$E29ID"   # 恢复探针届时可认领
    m2_wait_journal_body "写请求已达 stub（CONFIRM 前窗口）" 300 POST "/repos/stuborg/stubrepo/check-runs" "$CK" 1 || true
    local ST; ST=$(m2_kill_app publisher-app)
    [ "$ST" != "running" ] && ok "publisher 已 SIGKILL（State=$ST）" || bad "publisher 仍在运行（$ST）"
    m2_post_check_override_off
    m2_start_app publisher-app || true
    # 恢复扫描：IN_FLIGHT 租约过期（publisher.lease-seconds 默认 60s）→ RECONCILING → 探针认领
    m2_wait_sql "CREATE_CHECK CONFIRMED（reconcile 探针认领）" 600 "CONFIRMED" \
        "select state from outbox_command where operation_id='$CK'" || true
    m2_wait_sql "outbox CONFIRMED=2" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks.json"
    assert_eq "stub check-runs POST 恰 1 次（远端恰好一个对象，无重复创建）" \
        "$(m2_journal_count "$CASE_DIR/stub-checks.json")" "1"
    m2_journal_find POST "\"url\":\"/repos/stuborg/stubrepo/pulls/$PR/reviews\"" "$CASE_DIR/stub-reviews.json"
    assert_eq "stub reviews POST 恰 1 次" "$(m2_journal_count "$CASE_DIR/stub-reviews.json")" "1"
    assert_eq "PUBLICATION_OUTCOME_UNKNOWN 事件留痕" "$(m2_pr_event_count "$PR" PUBLICATION_OUTCOME_UNKNOWN)" "1"
    assert_eq "check 资源恰 1 行" \
        "$(m2_psql "select count(*) from publication_resource where created_by_operation_id='$CK'" | tr -d '[:space:]')" "1"
    m2_register_pr_resources "$PR" || true
    end_case E2E-29
}

# ---------------------------------------------------------------- E2E-30
# 三种在途形态分别 compose down/up → 全从 PG 恢复，零内存态丢失
e2e_30() {
    # ---- 形态 A：checkpoint 已提交（T2 未完/后） ----
    begin_case E2E-30-A "compose down/up：checkpoint 已提交"
    m2_journal_reset
    m2_model_delay_on 45000
    local PRA=$((24000 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PRA" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e30a)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "checkpoint 落库" 300 "1" \
        "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PRA" || true
    docker compose down > "$CASE_DIR/down.log" 2>&1
    docker compose up -d > "$CASE_DIR/up.log" 2>&1
    m2_model_delay_off   # 注：stub 已随 down/up 重建，运行时映射天然清空；此行幂等兜底
    m2_stack_ready || true
    m2_probe_sync_republish_all   # TB-13：栈重建后按状态文件恢复探针可见映射
    # 保护窗：若 down 时 CREATE_CHECK 处于 IN_FLIGHT，恢复探针先于正常发布路径 firing；
    # outbox 一铸出即补注探针可见映射（publisher 恢复扫描要等租约过期，注入窗口充足）
    m2_wait_sql "A：outbox 铸出" 900 "t" \
        "select count(*) >= 1 $(m2_pr_sql_where "$PRA") and oc.command_type='CREATE_CHECK'" || true
    local CKA; CKA=$(m2_pr_op "$PRA" CREATE_CHECK)
    # TB-12：探针预注册 id 取唯一值（探针认领子情形会以探针条目 id 登记资源行，固定 7000001 跨轮撞库）
    [ -n "$CKA" ] && m2_check_present_add "$CKA" "$((7150000 + RANDOM % 50000))"
    m2_wait_sql "PR#$PRA outbox CONFIRMED=2（整栈重启后从 PG 恢复）" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PRA") and oc.state='CONFIRMED'" || true
    # stub journal 随容器重建清空 → 重启后模型计数应为 0（checkpoint 复用零重调）
    assert_eq "重启后模型计数=0（checkpoint 复用）" "$(m2_model_calls "$CASE_DIR/model.json")" "0"
    assert_eq "finding 恰 1 条（无重复）" "$(m2_pr_finding_count "$PRA")" "1"
    m2_register_pr_resources "$PRA" || true
    end_case E2E-30-A

    # ---- 形态 B：repair PENDING 在途 ----
    begin_case E2E-30-B "compose down/up：repair PENDING 在途"
    local PRB=$((24100 + RANDOM % 90))
    if m2_run_pr_e2e "$PRB" e30b 600; then ok "B 基线闭环"; else bad "B 基线未收敛"; fi
    local CKB RIDB NEWIDB=$((7600000 + RANDOM % 50000))
    CKB=$(m2_pr_op "$PRB" CREATE_CHECK CONFIRMED)
    RIDB=$(m2_resource_of_op "$CKB")
    m2_check_present_remove "$CKB"
    m2_force_drift_due "$RIDB"
    # 等"已铸单"即可（PENDING/DISPATCHED 皆"repair 在途"；planner 5s 级循环，
    # 轮询可能错过 PENDING 瞬态——两形态断言等价，见头注诚实清单）
    m2_wait_sql "B：repair_request 在途（PENDING/DISPATCHED）" 240 "1" \
        "select count(*) from repair_request where publication_resource_id='$RIDB'" || true
    local REQ0
    REQ0=$(m2_psql "select count(*) from repair_request" | tr -d '[:space:]')
    docker compose down > "$CASE_DIR/down.log" 2>&1
    docker compose up -d > "$CASE_DIR/up.log" 2>&1
    # stub 重建后运行时映射全失：静态探针空列表=删除态保持；修复重建需新 id——
    # 必须在 control/publisher 完成启动（planner/claimer 开工）前注入，故等 stub
    # admin 先就绪即注入，再等整栈
    m2_wait_for "stub admin 就绪" 120 bash -c "curl -s -o /dev/null $(m2_stub_admin)/__admin/mappings" || true
    m2_post_check_override_on "$NEWIDB"
    m2_stack_ready || true
    m2_probe_sync_republish_all   # TB-13：栈重建后恢复探针映射（被摘除对象保持删除态）
    m2_wait_sql "B：修复单 REPAIRED（从 PG 恢复继续）" 600 "REPAIRED" \
        "select state from repair_request where publication_resource_id='$RIDB'" || true
    local REQB ROPB NEWRIDB
    REQB=$(m2_request_of_resource "$RIDB")
    ROPB=$(m2_request_field "$REQB" repair_operation_id)
    NEWRIDB=$(m2_psql "select id from publication_resource where replaces_resource_id='$RIDB' and state='PRESENT'" | tr -d '[:space:]')
    [ -n "$NEWRIDB" ] && ok "B：新 PRESENT 行链回" || bad "B：缺新 PRESENT 行"
    assert_eq "B：repair_request 总数不变（重启不铸重复单）" \
        "$(m2_psql "select count(*) from repair_request" | tr -d '[:space:]')" "$REQ0"
    assert_eq "B：REPAIR Run 恰 1 个" \
        "$(m2_psql "select count(*) from review_run rr join repair_request q on q.repair_run_id=rr.id where q.id='$REQB'" | tr -d '[:space:]')" "1"
    m2_post_check_override_off
    [ -n "$ROPB" ] && m2_check_present_add "$ROPB" "$NEWIDB"
    end_case E2E-30-B

    # ---- 形态 C：repair 命令 IN_FLIGHT 在途 ----
    begin_case E2E-30-C "compose down/up：repair 命令 IN_FLIGHT"
    m2_journal_reset
    local PRC=$((24200 + RANDOM % 90))
    if m2_run_pr_e2e "$PRC" e30c 600; then ok "C 基线闭环"; else bad "C 基线未收敛"; fi
    local CKC RIDC NEWIDC=$((7700000 + RANDOM % 50000))
    CKC=$(m2_pr_op "$PRC" CREATE_CHECK CONFIRMED)
    RIDC=$(m2_resource_of_op "$CKC")
    m2_post_check_delay_on 20000 "$NEWIDC"   # repair 写延迟 + 新远端 id
    m2_check_present_remove "$CKC"
    m2_force_drift_due "$RIDC"
    m2_wait_sql "C：repair DISPATCHED" 300 "DISPATCHED" \
        "select state from repair_request where publication_resource_id='$RIDC'" || true
    local REQC ROPC
    REQC=$(m2_request_of_resource "$RIDC")
    ROPC=$(m2_request_field "$REQC" repair_operation_id)
    m2_wait_journal_body "C：repair 写已达 stub（IN_FLIGHT 窗口）" 300 POST \
        "/repos/stuborg/stubrepo/check-runs" "$ROPC" 1 || true
    # TB-26：stub journal 是内存态，down/up 即清空——必须先取证崩溃前写，再拆栈
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks-pre.json"
    local WRITEC_PRE
    WRITEC_PRE=$(jq --arg s "$ROPC" '[.requests[].body | select(contains($s))] | length' "$CASE_DIR/stub-checks-pre.json" 2>/dev/null || echo 0)
    docker compose down > "$CASE_DIR/down.log" 2>&1
    docker compose up -d > "$CASE_DIR/up.log" 2>&1
    m2_stack_ready || true
    m2_probe_sync_republish_all   # TB-13：栈重建后恢复探针映射
    # 恢复探针认领：注册 repair 命令 external_id 可见（id=延迟映射约定的新 id）
    m2_check_present_add "$ROPC" "$NEWIDC"
    m2_wait_sql "C：repair 命令 CONFIRMED（reconcile 认领）" 600 "CONFIRMED" \
        "select state from outbox_command where operation_id='$ROPC'" || true
    m2_wait_sql "C：修复单 REPAIRED" 300 "REPAIRED" \
        "select state from repair_request where id='$REQC'" || true
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks.json"
    local WRITEC
    WRITEC=$(jq --arg s "$ROPC" '[.requests[].body | select(contains($s))] | length' "$CASE_DIR/stub-checks.json" 2>/dev/null || echo 0)
    assert_eq "C：崩溃前 repair 远端写恰 1 次" "$WRITEC_PRE" "1"
    assert_eq "C：恢复后 repair 远端零重复写" "$WRITEC" "0"
    local NEWRIDC
    NEWRIDC=$(m2_psql "select id from publication_resource where replaces_resource_id='$RIDC' and state='PRESENT'" | tr -d '[:space:]')
    [ -n "$NEWRIDC" ] && ok "C：新 PRESENT 行链回" || bad "C：缺新 PRESENT 行"
    assert_eq "C：新行 remote_id" "$(m2_resource_field "$NEWRIDC" remote_id)" "$NEWIDC"
    end_case E2E-30-C
}

# ---------------------------------------------------------------- E2E-31
# repair 执行中 PR push 新 commit → epoch fence 挡住、request EXPIRED（I22）
e2e_31() {
    begin_case E2E-31 "repair 执行中 push 新 commit → epoch fence + EXPIRED"
    m2_journal_reset
    # TB-17：原时序"等铸单（3s 轮询）→ pause"存在数秒窗口，repair 命令可在 pause 前
    # 被 claim 并 POST（fence 本身无缺陷）。修正：0.5s 密轮询等铸单、命中即冻结
    # publisher，随后显式检测"命令是否已抢跑"；抢跑则换 PR 重试（最多 3 轮）。
    # TB-20：docker pause 会连 publisher 的 T14 只读 token 窄接口一起冻结——控制面处理
    # synchronize webhook 必须经该口取 token，pause 期间换届只能 RETRY_WAIT（实测迟到
    # 4 分钟），unpause 后 epoch 尚未 bump、fence 合法放行（fence 语义无缺陷）。
    # 修正：pause 仅覆盖"铸单→行锁落地"窗口；随后后台 psql 持 repair 命令行
    # FOR UPDATE（claim 走 SKIP LOCKED 必跳过、T3-A lockCommand 必阻塞），unpause
    # 让控制面取 token 完成换届，bump 落定后放锁，fence/sweep 确定性 SUPERSEDED。
    local PR CK RID EPOCH0 REQ ROP OST ATTEMPT READY=0 LOCK_PID="" LOCK_BE=""
    local LOCK_LOG="$CASE_DIR/e31-lock.log"
    for ATTEMPT in 1 2 3; do
        PR=$((25000 + RANDOM % 500))
        if m2_run_pr_e2e "$PR" "e31-a$ATTEMPT" 600; then ok "基线闭环（第 $ATTEMPT 轮）"; else bad "基线未收敛"; fi
        CK=$(m2_pr_op "$PR" CREATE_CHECK CONFIRMED)
        RID=$(m2_resource_of_op "$CK")
        EPOCH0=$(m2_psql "select publication_epoch from pr_subject where repository_full_name='$M2_REPO' and pr_number=$PR" | tr -d '[:space:]')
        m2_check_present_remove "$CK"
        m2_force_drift_due "$RID"
        local deadline=$((SECONDS + 240)) minted=0
        while [ $SECONDS -lt $deadline ]; do
            [ "$(m2_psql "select count(*) from repair_request where publication_resource_id='$RID'" 2>/dev/null | tr -d '[:space:]')" = "1" ] \
                && { minted=1; break; }
            sleep 0.5
        done
        # 冻结 publisher：仅覆盖"铸单→行锁落地"窗口（TB-20：pause 期间控制面无法换届）
        docker pause "$(docker compose ps -q publisher-app)" > /dev/null 2>&1
        [ "$minted" = "1" ] && ok "repair 单已铸+publisher 已冻结" || bad "repair 单未铸出（第 $ATTEMPT 轮）"
        if ! m2_wait_sql "planner 已派发 DISPATCHED" 180 "DISPATCHED" \
            "select state from repair_request where publication_resource_id='$RID'"; then
            note "第 $ATTEMPT 轮：repair 单未派发——unpause 后换 PR 重试"
            docker unpause "$(docker compose ps -q publisher-app)" > /dev/null 2>&1
            continue
        fi
        REQ=$(m2_request_of_resource "$RID")
        ROP=$(m2_request_field "$REQ" repair_operation_id)
        OST=$(m2_psql "select state from outbox_command where operation_id='$ROP'" | tr -d '[:space:]')
        if [ "$OST" != "PENDING" ]; then
            note "第 $ATTEMPT 轮注入竞态：命令已 $OST（pause 前被 claim）——换 PR 重试"
            docker unpause "$(docker compose ps -q publisher-app)" > /dev/null 2>&1
            continue
        fi
        # 行锁代 pause（TB-20）：后台 psql 持 ROP 行 FOR UPDATE——claim（SKIP LOCKED）
        # 必跳过、T3-A lockCommand 必阻塞、publisher 保持存活供控制面 T14 token 口
        : > "$LOCK_LOG"
        docker compose exec -T postgres psql -U postgres -d pr_agent >"$LOCK_LOG" 2>&1 <<SQL &
BEGIN;
SELECT 'LOCKPID=' || pg_backend_pid();
SELECT operation_id FROM outbox_command WHERE operation_id='$ROP' FOR UPDATE;
SELECT 'LOCK_HELD';
SELECT pg_sleep(300);
ROLLBACK;
SQL
        LOCK_PID=$!
        local i
        for i in $(seq 1 75); do grep -q LOCK_HELD "$LOCK_LOG" 2>/dev/null && break; sleep 0.2; done
        LOCK_BE=$(grep -o 'LOCKPID=[0-9]*' "$LOCK_LOG" | head -1 | cut -d= -f2)
        if ! grep -q LOCK_HELD "$LOCK_LOG" || [ -z "$LOCK_BE" ]; then
            bad "行锁未落地（第 $ATTEMPT 轮，装备故障）——用例无效"
            docker unpause "$(docker compose ps -q publisher-app)" > /dev/null 2>&1
            kill "$LOCK_PID" 2>/dev/null; wait "$LOCK_PID" 2>/dev/null
            end_case E2E-31
            return
        fi
        docker unpause "$(docker compose ps -q publisher-app)" > /dev/null 2>&1
        READY=1; break
    done
    if [ "$READY" != "1" ]; then
        bad "三轮均遇注入竞态，用例无法有效执行"
        end_case E2E-31
        return
    fi
    ok "repair 命令 PENDING 行锁持有+publisher 已恢复（T14 token 口可用）"
    # push 新 commit：权威读元数据同步换届（stub 静态映射 head 固定，需运行时覆盖）
    local NEW_SHA="eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    m2_pr_meta_override_on "$PR" "$NEW_SHA"
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" synchronize "$NEW_SHA" "$M2_BASE_SHA" e31-sync)
    assert_eq "synchronize webhook 受理" "$HTTP" "202"
    # TB-20：换届超时=用例无效（原 || true 掩盖了 pause 锁死 token 口导致的换届迟到）
    if ! m2_wait_sql "publication_epoch 换届" 180 "t" \
        "select publication_epoch > $EPOCH0 from pr_subject where repository_full_name='$M2_REPO' and pr_number=$PR"; then
        bad "epoch 换届超时（控制面未能处理 sync webhook）——用例无效"
        [ -n "$LOCK_BE" ] && m2_psql "select pg_terminate_backend($LOCK_BE)" > /dev/null 2>&1
        kill "$LOCK_PID" 2>/dev/null; wait "$LOCK_PID" 2>/dev/null
        m2_pr_meta_override_off
        end_case E2E-31
        return
    fi
    # 换届落定后放行：fence（T3-A）或 sweep（兜底路径③）确定性 SUPERSEDED
    [ -n "$LOCK_BE" ] && m2_psql "select pg_terminate_backend($LOCK_BE)" > /dev/null 2>&1
    kill "$LOCK_PID" 2>/dev/null; wait "$LOCK_PID" 2>/dev/null
    if ! m2_wait_sql "repair 命令被 epoch fence 拒（SUPERSEDED）" 300 "SUPERSEDED" \
        "select state from outbox_command where operation_id='$ROP'"; then
        bad "repair 命令未被 epoch fence SUPERSEDED"
    fi
    if ! m2_wait_sql "request EXPIRED（projector 收敛）" 180 "EXPIRED" \
        "select state from repair_request where id='$REQ'"; then
        bad "repair_request 未收敛 EXPIRED"
    fi
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks.json"
    local W
    W=$(jq --arg s "$ROP" '[.requests[].body | select(contains($s))] | length' "$CASE_DIR/stub-checks.json" 2>/dev/null || echo 0)
    assert_eq "stub 零写（repair 命令未触网）" "$W" "0"
    assert_eq "REPAIR_EXPIRED 事件留痕" "$(m2_pr_event_count "$PR" REPAIR_EXPIRED)" "1"
    m2_pr_meta_override_off
    echo "  复原：publisher 已 unpause、行锁已释放、元数据覆盖映射已摘除" | tee -a "$SUMMARY"
    end_case E2E-31
}

# ---------------------------------------------------------------- E2E-32
# 三调用点分别整栈注入 429+Retry-After → 各自退避精确生效（I23）
e2e_32() {
    # ---- A：写路径（FencedPublicationExecutor.markRetryWait） ----
    begin_case E2E-32-A "429+Retry-After：写路径"
    m2_journal_reset
    m2_fault_on post-check 429 30
    local T0; T0=$(m2_db_epoch)
    local PRA=$((26000 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PRA" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e32a)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "CREATE_CHECK 退避 RETRY_WAIT" 300 "RETRY_WAIT" \
        "select oc.state $(m2_pr_sql_where "$PRA") and oc.command_type='CREATE_CHECK'" || true
    local NA
    NA=$(m2_psql "select extract(epoch from oc.next_attempt_at)::bigint $(m2_pr_sql_where "$PRA") and oc.command_type='CREATE_CHECK'" | tr -d '[:space:]')
    [ -n "$NA" ] && [ "$NA" -ge $((T0 + 25)) ] \
        && ok "写路径退避尊重 Retry-After=30（next_attempt_at-T0=$((NA - T0))s）" \
        || bad "写路径退避未尊重 Retry-After：next_attempt_at=$NA T0=$T0"
    # TB-23：全局计数会被邻案迟到写污染（E2E-31 的 synchronize 换届评审管线可在本案
    # 窗口内完成并 POST /check-runs——sha=eeee…、external_id 不同源）；断言只数本案命令
    local CKA; CKA=$(m2_pr_op "$PRA" CREATE_CHECK)
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks.json"
    assert_eq "退避窗口内无重试风暴（本案 POST 恰 1 次）" \
        "$(jq --arg s "$CKA" '[.requests[].body | select(contains($s))] | length' "$CASE_DIR/stub-checks.json" 2>/dev/null || echo 0)" "1"
    m2_fault_off post-check
    m2_wait_sql "A：窗口后自愈 CONFIRMED=2" 300 "2" \
        "select count(*) $(m2_pr_sql_where "$PRA") and oc.state='CONFIRMED'" || true
    m2_register_pr_resources "$PRA" || true
    end_case E2E-32-A

    # ---- B：恢复扫描（OutboxRecoveryScanner） ----
    begin_case E2E-32-B "429+Retry-After：恢复扫描"
    m2_journal_reset
    local E32BID=$((7150000 + RANDOM % 50000))   # TB-12：延迟响应与探针预注册同源唯一
    m2_post_check_delay_on 15000 "$E32BID"
    local PRB=$((26100 + RANDOM % 90))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PRB" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e32b)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "CREATE_CHECK 已铸" 300 "t" \
        "select count(*) >= 1 $(m2_pr_sql_where "$PRB") and oc.command_type='CREATE_CHECK'" || true
    local CKB; CKB=$(m2_pr_op "$PRB" CREATE_CHECK)
    m2_wait_journal_body "写已达 stub" 300 POST "/repos/stuborg/stubrepo/check-runs" "$CKB" 1 || true
    m2_kill_app publisher-app > /dev/null
    m2_post_check_override_off
    m2_fault_on list-check 429 30   # 恢复探针吃 429
    m2_start_app publisher-app || true
    m2_wait_sql "命令转 RECONCILING" 300 "RECONCILING" \
        "select state from outbox_command where operation_id='$CKB'" || true
    local TOBS RA
    TOBS=$(m2_db_epoch)
    RA=$(m2_psql "select extract(epoch from reconcile_after)::bigint from outbox_command where operation_id='$CKB'" | tr -d '[:space:]')
    # Retry-After=30 生效 ⇒ reconcile_after-观察时刻 ≈ 30s 以内量级；
    # 缺省 unknown 回退是 120s（publisher.reconcile.unknown-retry-delay-seconds）→ 可区分
    [ -n "$RA" ] && [ "$RA" -le $((TOBS + 70)) ] && [ "$RA" -ge $((TOBS - 10)) ] \
        && ok "恢复扫描退避尊重 Retry-After=30（reconcile_after-TOBS=$((RA - TOBS))s；缺省回退 120s 可区分）" \
        || bad "恢复扫描退避异常：reconcile_after=$RA TOBS=$TOBS"
    m2_journal_find GET '"urlPathPattern":"/repos/[^/]+/[^/]+/commits/[^/]+/check-runs"' "$CASE_DIR/probes.json"
    [ "$(m2_journal_count "$CASE_DIR/probes.json")" -ge 1 ] && ok "恢复探针请求留痕（journal）" || bad "恢复探针无 journal 记录"
    m2_fault_off list-check
    m2_check_present_add "$CKB" "$E32BID"
    m2_wait_sql "B：窗口后自愈 CONFIRMED=2" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PRB") and oc.state='CONFIRMED'" || true
    m2_register_pr_resources "$PRB" || true
    end_case E2E-32-B

    # ---- C：巡检路径（DriftReconciler.markCheckError） ----
    begin_case E2E-32-C "429+Retry-After：drift 巡检"
    local PRC=$((26200 + RANDOM % 90))
    if m2_run_pr_e2e "$PRC" e32c 600; then ok "C 基线闭环"; else bad "C 基线未收敛"; fi
    local CKC RIDC
    CKC=$(m2_pr_op "$PRC" CREATE_CHECK CONFIRMED)
    RIDC=$(m2_resource_of_op "$CKC")
    local T0C; T0C=$(m2_db_epoch)
    m2_fault_on list-check 429 30
    m2_force_drift_due "$RIDC"
    m2_wait_sql "drift 探针失败计数 +1" 240 "t" \
        "select check_error_count >= 1 from publication_resource where id='$RIDC'" || true
    local NC
    NC=$(m2_psql "select extract(epoch from next_check_at)::bigint from publication_resource where id='$RIDC'" | tr -d '[:space:]')
    # Retry-After=30 生效 ⇒ next_check_at ≈ T0+30~60s；正常巡检间隔 60min=3600s → 强区分
    [ -n "$NC" ] && [ "$NC" -ge $((T0C + 25)) ] && [ "$NC" -le $((T0C + 300)) ] \
        && ok "巡检退避尊重 Retry-After=30（next_check_at-T0=$((NC - T0C))s；正常间隔 3600s 可区分）" \
        || bad "巡检退避异常：next_check_at=$NC T0=$T0C"
    assert_eq "资源状态不误改（仍 PRESENT）" "$(m2_resource_field "$RIDC" state)" "PRESENT"
    assert_eq "零 repair 单（429 不铸单）" \
        "$(m2_psql "select count(*) from repair_request where publication_resource_id='$RIDC'" | tr -d '[:space:]')" "0"
    m2_fault_off list-check
    m2_force_drift_due "$RIDC"
    m2_wait_sql "探针恢复 error_count 归零" 240 "0" \
        "select check_error_count from publication_resource where id='$RIDC'" || true
    end_case E2E-32-C
}

# ---------------------------------------------------------------- E2E-33
# 修复成功后再次删除 → 第二轮修复完成；两轮资源行链完整（I26/ST-36）
e2e_33() {
    begin_case E2E-33 "修复成功后再次删除 → 第二轮修复"
    m2_journal_reset
    local PR=$((27000 + RANDOM % 500))
    if m2_run_pr_e2e "$PR" e33 600; then ok "基线闭环"; else bad "基线未收敛"; fi
    local CK RID1 NEW1=$((7800000 + RANDOM % 50000)) NEW2=$((7900000 + RANDOM % 50000))
    CK=$(m2_pr_op "$PR" CREATE_CHECK CONFIRMED)
    RID1=$(m2_resource_of_op "$CK")
    drift_repair_round "$PR" "$CK" "$RID1" "$NEW1" "R1"
    local RID2="$ROUND_NEW_RID" ROP1="$ROUND_ROP"
    # 第二轮：删掉重建出的新对象（探针映射里摘 ROP1）
    m2_check_present_remove "$ROP1"
    m2_post_check_override_on "$NEW2"
    m2_force_drift_due "$RID2"
    m2_wait_sql "R2：新行转 MISSING" 240 "MISSING" \
        "select state from publication_resource where id='$RID2'" || true
    m2_wait_sql "R2：第二张 repair 单（旧链 REPAIRED 不挡新单）" 120 "1" \
        "select count(*) from repair_request where publication_resource_id='$RID2'" || true
    local REQ2 ROP2
    REQ2=$(m2_request_of_resource "$RID2")
    m2_wait_sql "R2：修复单 REPAIRED" 300 "REPAIRED" \
        "select state from repair_request where id='$REQ2'" || true
    ROP2=$(m2_request_field "$REQ2" repair_operation_id)
    local RID3
    RID3=$(m2_psql "select id from publication_resource where replaces_resource_id='$RID2' and state='PRESENT'" | tr -d '[:space:]')
    assert_eq "R2：行1 REPAIRED" "$(m2_resource_field "$RID1" state)" "REPAIRED"
    assert_eq "R2：行2 REPAIRED" "$(m2_resource_field "$RID2" state)" "REPAIRED"
    assert_eq "R2：行2 repaired_by=第二轮 repair 命令" "$(m2_resource_field "$RID2" repaired_by_operation_id)" "$ROP2"
    [ -n "$RID3" ] && ok "R2：行3 PRESENT（三行链完整）" || bad "R2：缺第三轮 PRESENT 行"
    assert_eq "R2：行3 remote_id" "$(m2_resource_field "$RID3" remote_id)" "$NEW2"
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$CASE_DIR/stub-checks.json"
    assert_eq "stub check-runs POST 恰 3 次（原始+两轮重建）" "$(m2_journal_count "$CASE_DIR/stub-checks.json")" "3"
    m2_post_check_override_off
    [ -n "$ROP2" ] && m2_check_present_add "$ROP2" "$NEW2"
    end_case E2E-33
}

# ---------------------------------------------------------------- E2E-34
# 权限撤销致 404（sanity 失败）→ UNKNOWN + 权限告警、零 repair 单（ST-35/E2E-18）
e2e_34() {
    begin_case E2E-34 "权限撤销（404+sanity 失败）→ UNKNOWN+告警、零 repair"
    m2_journal_reset
    local PR=$((28000 + RANDOM % 500))
    if m2_run_pr_e2e "$PR" e34 600; then ok "基线闭环"; else bad "基线未收敛"; fi
    local CK RID
    CK=$(m2_pr_op "$PR" CREATE_CHECK CONFIRMED)
    RID=$(m2_resource_of_op "$CK")
    local REMOTE; REMOTE=$(m2_resource_field "$RID" remote_id)
    # 注入（TB-16 修正）：对象从 stub 摘除（探针 200 空列表 → 窗口穷尽 NOT_FOUND）
    # + sanity 读 404（模拟权限撤销；F-3：404 不可区分"不存在"与"无权限"）。
    # 原注入 list-check 端点级 404 撞上 M1 既定裁决——LIST 探针 404 归瞬时 UNKNOWN
    # 退避（sha 消失/瞬断/权限皆可能，方向安全零误修），不进 sanity 路径；
    # 权限告警路径的正确注入形态是"对象缺失（NOT_FOUND）+ sanity 失败"。
    m2_check_present_remove "$CK"
    m2_fault_on repo 404
    m2_force_drift_due "$RID"
    m2_wait_sql "资源 UNKNOWN（权限异常绝不冒充不存在）" 240 "UNKNOWN" \
        "select state from publication_resource where id='$RID'" || true
    assert_eq "零 repair 单（sanity 失败不铸单）" \
        "$(m2_psql "select count(*) from repair_request where publication_resource_id='$RID'" | tr -d '[:space:]')" "0"
    assert_eq "PUBLICATION_DRIFT_PERMISSION_ALERT 告警留痕" \
        "$(m2_pr_event_count "$PR" PUBLICATION_DRIFT_PERMISSION_ALERT)" "1"
    m2_fault_off repo
    # 恢复段（EX-28 v1.2 裁定：人工复位制）
    # (a) 确认不自动复归（既定行为）：拨回到期后等过一轮巡检，UNKNOWN 仍不在扫描集
    m2_force_drift_due "$RID"
    if m2_wait_sql "观察窗：是否自动复归" 90 "PRESENT" \
        "select state from publication_resource where id='$RID'"; then
        bad "UNKNOWN 自动复归（违反 EX-28 v1.2 裁定的不回队语义）"
    else
        ok "确认不自动复归（UNKNOWN 不在巡检扫描集，既定行为）"
    fi
    # (b) runbook 人工复位 SQL + 探针回填对象（此前已摘除）→ 下一轮巡检正常探测、恢复收敛
    m2_check_present_add "$CK" "$REMOTE"
    m2_psql "update publication_resource set state='PRESENT', next_check_at=now() where id='$RID'" > /dev/null
    m2_wait_sql "复位后复归 PRESENT（check_error_count 清零）" 240 "PRESENT" \
        "select state from publication_resource where id='$RID' and check_error_count=0" || true
    echo "  复原：sanity 404 故障映射已摘除、探针对象已回填" | tee -a "$SUMMARY"
    end_case E2E-34
}

# ---------------------------------------------------------------- 调度
m2_stub_github_mode || { echo "E2E-26~34 需 stub GitHub 模式（GITHUB_API_BASE 指 github-stub）；真实模式回归用 e2e-35-real.sh"; exit 1; }
case "$CASE" in
    E2E-26)
        m2_stub_model_mode || { echo "E2E-26 需 stub 模型模式（模型计数经 stub journal 取证）"; exit 1; }
        e2e_26 ;;
    E2E-27)
        m2_stub_model_mode || { echo "E2E-27 需 stub 模型模式（双 Worker 双调用，真实模型双费）"; exit 1; }
        e2e_27 ;;
    E2E-28) e2e_28 ;;
    E2E-29) e2e_29 ;;
    E2E-30)
        m2_stub_model_mode || { echo "E2E-30 需 stub 模型模式（形态 A 模型计数断言）"; exit 1; }
        e2e_30 ;;
    E2E-31) e2e_31 ;;
    E2E-32) e2e_32 ;;
    E2E-33) e2e_33 ;;
    E2E-34) e2e_34 ;;
    all)
        if m2_stub_model_mode; then
            SUB=all; e2e_26; e2e_27; e2e_28; e2e_29; e2e_30; e2e_31; e2e_32; e2e_33; e2e_34
        else
            skip_all_note="非 stub 模型模式：跳过 E2E-26/27/30"
            echo "[SKIP] $skip_all_note"
            e2e_28; e2e_29; e2e_31; e2e_32; e2e_33; e2e_34
        fi ;;
    *) usage ;;
esac

echo "=================================================="
echo "E2E 结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE"
[ "$FAIL" -eq 0 ]
