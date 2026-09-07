#!/bin/sh
# ============================================================================
# e2e-m4-02-f2.sh —— E2E-M4-02：B2/F2 分别提供 / 移除 Change fixture 两轮
#                     （LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：有证据时 Claim 引用正确 artifact；缺失时只准
# PARTIAL/UNRESOLVED；两轮 Snapshot digest 不同、旧报告不被反改。
#
# 触发：driver.py phase1 F2 × 两轮；每轮 Holmes 主链收敛后经《195-部署配方-v1.md》
#       §5 方式 A 一次性入口触发影子 run。轮 B "fixture 移除" = 影子实例以
#       --app.alert.am4.allowed-tools 裁掉 change.query（源不可用的等价语义：
#       ChangeAgent POLICY_DENIED → 任务 DEAD 降级续跑，零 change 证据落库）。
#
# v2（195 迭代修正，合并 BA-36 T0 窗）：run 认领只认 T0 窗内新行（报告 join 面
# 天然排除影子 run）；claim/证据/快照断言全部对齐影子面（holmes 主链不产
# rca_claim/rca_evidence/rca_evidence_snapshot，v1 混查 holmes run 恒空真）。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

POLL_MAX="${POLL_MAX:-240}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/build/pr/deploy}"

e4_begin

trigger_shadow() {
    HOLMES_ID="$1"
    shift
    TRIGGER_OUT=$( (cd "$DEPLOY_DIR" && docker compose run --rm --no-deps control-app \
        --spring.profiles.active=docker,am4-shadow-trigger \
        --spring.main.web-application-type=none \
        --am4.shadow-trigger.holmes-run-id="$HOLMES_ID" "$@") </dev/null 2>&1 ) \
        || { printf '%s\n' "$TRIGGER_OUT" | tail -30; return 1; }
    printf '%s\n' "$TRIGGER_OUT" | grep "^AM4_SHADOW_RUN_ID=" | tail -1 | cut -d= -f2
}

wait_report_since() {
    i=0
    while [ $i -lt "$POLL_MAX" ]; do
        DONE=$(e4_sql "
            select count(*) from rca_run r join rca_report rr on rr.run_id = r.id
             where r.created_at >= '$1' and r.state in ('SUCCEEDED','PARTIAL')")
        [ "$DONE" -ge 1 ] && return 0
        i=$((i + 5)); sleep 5
    done
    return 1
}

holmes_since() {
    e4_sql "
        select r.id from rca_run r
         where r.created_at >= '$1' and r.state in ('SUCCEEDED','PARTIAL')
           and exists (select 1 from rca_report rr where rr.run_id = r.id)
         order by r.created_at desc limit 1"
}

# ---------------- 轮 A：F2 注入 + change fixture 在位 ----------------
# 注入前静止面（quiesce）：上轮同 fault 会话必须先恢复归零（否则告警不重发 webhook）
echo "[E2E-M4-02] 注入前静止面（quiesce F2）"
e4_quiesce F2
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "[E2E-M4-02] 轮 A：F2 注入 + change fixture 在位（T0=$T0）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F2
wait_report_since "$T0" || { echo "  FAIL: 轮 A 未收敛"; exit 1; }
RUN_A=$(holmes_since "$T0")
echo "  runA=$RUN_A"

echo "[E2E-M4-02] 触发影子轮 A（change 源在位）"
SHADOW_A=$(trigger_shadow "$RUN_A")
[ -n "$SHADOW_A" ] || { echo "  FAIL: 影子轮 A 触发失败"; exit 1; }
echo "  shadowA=$SHADOW_A"

# ---------------- 轮 B：F2 注入 + change 源移除 ----------------
# 轮间静止面：轮 A 会话恢复归零后轮 B 才能形成新的 resolve→refire（新 run）
echo "[E2E-M4-02] 轮间静止面（quiesce F2）"
e4_quiesce F2
T0B=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "[E2E-M4-02] 轮 B：F2 注入 + change 源移除（T0B=$T0B）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F2
wait_report_since "$T0B" || { echo "  FAIL: 轮 B 未收敛"; exit 1; }
RUN_B=$(holmes_since "$T0B")
echo "  runB=$RUN_B"

echo "[E2E-M4-02] 触发影子轮 B（allowed-tools 裁掉 change.query）"
SHADOW_B=$(trigger_shadow "$RUN_B" \
    --app.alert.am4.allowed-tools=prometheus.query,logs.query)
[ -n "$SHADOW_B" ] || { echo "  FAIL: 影子轮 B 触发失败"; exit 1; }
echo "  shadowB=$SHADOW_B"

# ---------------- 断言（影子面） ----------------
# 有证据轮：Claim 引用 artifact（evidence_refs 非空，指向证据面）
CHANGE_A=$(e4_sql "select count(*) from rca_evidence where run_id='$SHADOW_A' and source='change'")
if [ "$CHANGE_A" -gt 0 ]; then
    e4_assert_eq "轮 A（有变更证据）Claim 引用 artifact" "$(e4_sql "
        select count(*) from rca_claim
         where run_id='$SHADOW_A' and (evidence_refs is null or evidence_refs::text='[]')")" "0"
else
    echo "  INFO: 轮 A 无 change 证据（fixture 未生效？检查 AlertAm4Config 注册面）"
fi

# 缺失轮：change 证据必为 0（源被裁）且零确定性 TRUE 变更根因
CHANGE_B=$(e4_sql "select count(*) from rca_evidence where run_id='$SHADOW_B' and source='change'")
e4_assert_eq "轮 B（源移除）零 change 证据" "$CHANGE_B" "0"
e4_assert_eq "轮 B 零确定性 TRUE 变更根因" "$(e4_sql "
    select count(*) from rca_claim
     where run_id='$SHADOW_B' and status='TRUE'
       and evidence_basis in ('SINGLE_SOURCE','MULTI_SOURCE_CONSISTENT')
       and claim_key like '%change%'")" "0"
# 缺失轮只准诚实分支：run 不伪造 SUCCEEDED 之外的完整成功语义（影子 = REPORTING）
e4_assert_eq "轮 B 影子诚实终态（REPORTING，不伪造成功）" "$(e4_sql "
    select count(*) from rca_run where id='$SHADOW_B' and state='REPORTING'")" "1"

# 两轮 Snapshot digest 不同（V16 快照表按 run 隔离；影子 run 各冻结一份）
SNAP_A=$(e4_sql "
    select distinct snapshot_digest from rca_evidence_snapshot where run_id='$SHADOW_A' limit 1")
SNAP_B=$(e4_sql "
    select distinct snapshot_digest from rca_evidence_snapshot where run_id='$SHADOW_B' limit 1")
e4_assert_eq "两轮 snapshot_digest 存在" "$([ -n "$SNAP_A" ] && [ -n "$SNAP_B" ] && echo yes || echo no)" "yes"
if [ "$CHANGE_A" -gt 0 ] && [ "$CHANGE_B" -eq 0 ]; then
    e4_assert_eq "fixture 变化必须改变 snapshot" "$([ "$SNAP_A" != "$SNAP_B" ] && echo yes || echo no)" "yes"
fi

# 旧报告不被反改：轮 A 报告行数在轮 B 后仍为原值（行级不变量，holmes 面）
e4_assert_eq "轮 A 报告恰一行（未被轮 B 反改）" "$(e4_sql "
    select count(*) from rca_report where run_id='$RUN_A'")" "1"

e4_summary
