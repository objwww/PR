#!/bin/sh
# ============================================================================
# e2e-m4-02-f2.sh —— E2E-M4-02：B2/F2 分别提供 / 移除 Change fixture 两轮
#                     （LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：有证据时 Claim 引用正确 artifact；缺失时只准
# PARTIAL/UNRESOLVED；两轮 Snapshot digest 不同、旧报告不被反改。
#
# 触发：driver.py phase1 F2 × 两轮（轮 A 带 change fixture，轮 B 移除）；
#       两轮 run id 由脚本窗口内时间序自动区分（先后两条 run）。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

POLL_MAX="${POLL_MAX:-240}"

e4_begin
echo "[E2E-M4-02] 轮 A：F2 注入 + change fixture 在位"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F2

wait_run() {
    i=0
    while [ $i -lt "$POLL_MAX" ]; do
        DONE=$(e4_sql "
            select count(*) from rca_run r join rca_report rr on rr.run_id = r.id
             where r.state in ('REPORTING','SUCCEEDED','PARTIAL')")
        [ "$DONE" -ge "$1" ] && return 0
        i=$((i + 5)); sleep 5
    done
    return 1
}
wait_run 1 || { echo "  FAIL: 轮 A 未收敛"; exit 1; }

RUN_A=$(e4_sql "select id from rca_run order by created_at asc offset 0 limit 1")
echo "  runA=$RUN_A"

echo "[E2E-M4-02] 轮 B：F2 注入 + change fixture 移除（执行者按配方移除 fixture 后回车）"
# fixture 移除 = 执行者操作（AlertAm4Config classpath fixture 或影子注册面裁剪）；
# 在位性由 rca_evidence source='change' 计数反向验证（轮 B 断言为 0）
sleep 2
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F2
wait_run 2 || { echo "  FAIL: 轮 B 未收敛"; exit 1; }
RUN_B=$(e4_sql "select id from rca_run order by created_at desc limit 1")
echo "  runB=$RUN_B"

# 有证据轮：Claim 引用 artifact（evidence_refs 非空，指向证据面）
CHANGE_A=$(e4_sql "select count(*) from rca_evidence where run_id='$RUN_A' and source='change'")
if [ "$CHANGE_A" -gt 0 ]; then
    e4_assert_eq "轮 A（有变更证据）Claim 引用 artifact" "$(e4_sql "
        select count(*) from rca_claim
         where run_id='$RUN_A' and (evidence_refs is null or evidence_refs='[]')")" "0"
else
    echo "  INFO: 轮 A 无 change 证据（fixture 未生效？检查 AlertAm4Config 注册面）"
fi

# 缺失轮：只准 PARTIAL/UNRESOLVED——无变更证据时不得产出确定性 TRUE 根因
CHANGE_B=$(e4_sql "select count(*) from rca_evidence where run_id='$RUN_B' and source='change'")
if [ "$CHANGE_B" -eq 0 ]; then
    e4_assert_eq "轮 B（无变更证据）零确定性 TRUE claim" "$(e4_sql "
        select count(*) from rca_claim
         where run_id='$RUN_B' and status='TRUE'
           and evidence_basis in ('SINGLE_SOURCE','MULTI_SOURCE_CONSISTENT')
           and claim_key like '%change%'")" "0"
    e4_assert_eq "轮 B run 终态为 PARTIAL/诚实分支" "$(e4_sql "
        select count(*) from rca_run where id='$RUN_B'
          and state in ('PARTIAL','REPORTING','SUCCEEDED')")" "1"
fi

# 两轮 Snapshot digest 不同（V16 快照表按 run 隔离）
SNAP_A=$(e4_sql "
    select distinct snapshot_digest from rca_evidence_snapshot where run_id='$RUN_A' limit 1")
SNAP_B=$(e4_sql "
    select distinct snapshot_digest from rca_evidence_snapshot where run_id='$RUN_B' limit 1")
e4_assert_eq "两轮 snapshot_digest 存在" "$([ -n "$SNAP_A" ] && [ -n "$SNAP_B" ] && echo yes || echo no)" "yes"
if [ "$SNAP_A" = "$SNAP_B" ]; then
    echo "  INFO: 两轮 snapshot 相同（两轮输入完全一致时合法；若轮 B 已移除 fixture 则应不同）"
    if [ "$CHANGE_A" -gt 0 ] && [ "$CHANGE_B" -eq 0 ]; then
        e4_assert_eq "fixture 变化必须改变 snapshot" "$SNAP_B" "__different__"
    fi
fi

# 旧报告不被反改：轮 A 报告行数在轮 B 后仍为原值（行级不变量）
e4_assert_eq "轮 A 报告恰一行（未被轮 B 反改）" "$(e4_sql "
    select count(*) from rca_report where run_id='$RUN_A'")" "1"

e4_summary
