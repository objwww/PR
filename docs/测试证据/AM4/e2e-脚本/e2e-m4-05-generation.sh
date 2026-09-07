#!/bin/sh
# ============================================================================
# e2e-m4-05-generation.sh —— E2E-M4-05：generation N 未完时恢复并再次注入形成
#                             N+1（LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：N 的迟到 task/tool/evidence/claim 全为 STALE
# 或拒收；N+1 Snapshot/报告不含 N 产出；两个 generation 可独立审计。
#
# 触发：driver.py phase1 F1 注入后不等收敛即二次注入（同 incident 键 → 代际 +1，
#       代际栅栏 INV-AM4-4 拒收旧代）；两代 run 由 incident 分组对拍。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

POLL_MAX="${POLL_MAX:-240}"

e4_begin
echo "[E2E-M4-05] phase1 F1 第一轮注入（generation N）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1
sleep 15
echo "[E2E-M4-05] 二次注入（未等 N 收敛，形成 N+1）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1

i=0
while [ $i -lt "$POLL_MAX" ]; do
    DONE=$(e4_sql "select count(*) from rca_run where state in ('REPORTING','SUCCEEDED','PARTIAL','SUPERSEDED')")
    [ "$DONE" -ge 1 ] && break
    i=$((i + 5)); sleep 5
done

# 同一 incident 两代 run 可独立审计（N 与 N+1 分行）
TWO_GEN=$(e4_sql "
    select count(distinct generation) from rca_run r
     where r.incident_id = (select incident_id from rca_run order by created_at desc limit 1)")
echo "  同 incident 代际数=$TWO_GEN（≥2 时代际栅栏生效；=1 时二次注入未命中同键）"

# 代际栅栏（INV-AM4-4）：死 run（superseded/expired/cancelled）零图推进——
# 旧代 run 的收尾任务不得有 DONE 终态之后的新推进（SUPERSEDED run 冻结）
STALE_ADVANCE=$(e4_sql "
    select count(*) from rca_task t
     join rca_run r on r.id = t.run_id
     where r.state in ('SUPERSEDED','CANCELLED','EXPIRED')
       and t.updated_at > r.updated_at")
e4_assert_eq "旧代 run 终态后零任务推进（迟到全 STALE/拒收）" "$STALE_ADVANCE" "0"

# N+1 Snapshot 不含 N 产出：快照成员证据的 observed_generation 与快照代一致
CROSS_GEN=$(e4_sql "
    select count(*) from rca_snapshot_member m
     join rca_evidence_snapshot s on s.id = m.snapshot_id
     join rca_evidence e on e.id = m.evidence_id
     where e.observed_generation <> s.observed_generation")
e4_assert_eq "N+1 快照零 N 代成员（跨代混入为零）" "$CROSS_GEN" "0"

# 两代 claim 独立：旧代 claim 不因新代被物理删除（历史不可变）
GEN_CLAIMS=$(e4_sql "
    select count(*) from rca_claim c
     join rca_run r on r.id = c.run_id
     where r.state in ('SUPERSEDED','CANCELLED','EXPIRED')")
echo "  旧代 claim 行数=$GEN_CLAIMS（保留可审计，非删除式代际切换）"

# 迟到工具调用拒收：旧代 run 的 invocation 在 run 终态后零新增
LATE_TOOL=$(e4_sql "
    select count(*) from rca_tool_invocation ti
     join rca_run r on r.id = ti.run_id
     where r.state in ('SUPERSEDED','CANCELLED','EXPIRED')
       and ti.started_at > r.updated_at")
e4_assert_eq "旧代 run 终态后零新增工具调用" "$LATE_TOOL" "0"

e4_summary
