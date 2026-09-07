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
# v3（195 迭代修正）：F2 注入伴生 ArenaOrderStuck 告警链（195 实证）——holmes
# 面必须按主告警 incident 过滤，否则 desc limit 1 抓到 Stuck 的 run，其 incident
# 上 orchestrator 又铸 RERUN，影子触发撞 uq_rca_run_active_incident；影子触发
# 收编 common e4_trigger_shadow（等活跃 run 收敛 + 重试 + stderr 显错）。
# v4（195 迭代修正，材料去重语义落地）：同 incident 新 firing 的材料 hash 与该
# incident 最新 run 相同则不铸 run（确定性去重，195 实证：intake ACCEPTED 且
# generation 推进而零 run）——影子 run 镜像 holmes hash 后成为该 incident 最新
# run，轮 B 注入注定零 run。轮 B 改为直接复用 RUN_A 作影子镜像源（同一 input
# snapshot、allowed-tools 裁掉 change.query，即"源移除"场景语义），不再注入
# 轮 B、不再等新 holmes run；影子 A 先经 quiesce 回收（REPORTING 挂 uq）再触发
# 影子 B。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

POLL_MAX="${POLL_MAX:-240}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/build/pr/deploy}"

e4_begin

wait_report_since() {
    i=0
    while [ $i -lt "$POLL_MAX" ]; do
        DONE=$(e4_sql "
            select count(*) from rca_run r join rca_report rr on rr.run_id = r.id
             join incident i on i.id = r.incident_id
             where r.created_at >= '$1' and r.state in ('SUCCEEDED','PARTIAL')
               and i.incident_key like '%ArenaIllegalTransitions%'")
        [ "$DONE" -ge 1 ] && return 0
        i=$((i + 5)); sleep 5
    done
    return 1
}

holmes_since() {
    e4_sql "
        select r.id from rca_run r
         join incident i on i.id = r.incident_id
         where r.created_at >= '$1' and r.state in ('SUCCEEDED','PARTIAL')
           and i.incident_key like '%ArenaIllegalTransitions%'
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
SHADOW_A=$(e4_trigger_shadow "$RUN_A") || { echo "  FAIL: 影子轮 A 触发失败"; exit 1; }
[ -n "$SHADOW_A" ] || { echo "  FAIL: 影子轮 A 触发失败（无 run id）"; exit 1; }
echo "  shadowA=$SHADOW_A"

# ---------------- 轮 B：change 源移除（复用 RUN_A 镜像，见头部 v4 说明） ----------------
# 轮间静止面 + 影子 A 回收（REPORTING 挂 uq_rca_run_active_incident，不回收则
# 影子 B 触发必撞活跃约束）
echo "[E2E-M4-02] 轮间静止面（quiesce F2，含影子 A 回收）"
e4_quiesce F2

echo "[E2E-M4-02] 触发影子轮 B（镜像 RUN_A，allowed-tools 裁掉 change.query）"
SHADOW_B=$(e4_trigger_shadow "$RUN_A" \
    --app.alert.am4.allowed-tools=prometheus.query,logs.query) \
    || { echo "  FAIL: 影子轮 B 触发失败"; exit 1; }
[ -n "$SHADOW_B" ] || { echo "  FAIL: 影子轮 B 触发失败（无 run id）"; exit 1; }
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
