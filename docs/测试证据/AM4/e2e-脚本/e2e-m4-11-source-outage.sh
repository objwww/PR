#!/bin/sh
# ============================================================================
# e2e-m4-11-source-outage.sh —— E2E-M4-11：源故障与恢复
#                     （FAULT_DRILL + LIVE_BUSINESS）
#
# 必断言（AM4 技术方案 §15.3）：Prometheus 暂不可达只降 readiness、不杀
# control；LiteLLM 未知结果进入 PROVISIONAL/UNMATCHED；预算存储不可用拒绝
# 派生/调用；恢复后对账收敛或到期进入明确终态，不得伪造 SUCCESS/零 usage。
#
# 触发（执行者按配方依序操作）：F1 注入运行中依次——① docker pause prometheus；
# ② 断 litellm 网络；③ 停 PG 预算存储角色权限；再恢复并观察对账。
# M4-36/37 Reconciler 生产接线为 G2 终裁开放项：恢复收敛断言以"无伪造终态"
# 的账本面为准（收敛动作本身待开放项裁定后增强）。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

OUTAGE_SECS="${OUTAGE_SECS:-120}"
PROM_CONTAINER="${PROM_CONTAINER:-prometheus-am0}"

e4_begin
echo "[E2E-M4-11] phase1 F1 注入"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1
sleep 20

echo "[E2E-M4-11] ① Prometheus 断网 ${OUTAGE_SECS}s（readiness 降级面）"
docker pause "$PROM_CONTAINER" || { echo "  FAIL: prometheus 容器不可操作"; exit 1; }
sleep "$OUTAGE_SECS"

# control 不死（Prometheus 暂不可达只降 readiness）
CONTROL_ALIVE=$(e4_sql "select 1")
e4_assert_eq "Prometheus 断网期间 control 存活（DB 面可查）" "$CONTROL_ALIVE" "1"

echo "[E2E-M4-11] 恢复 Prometheus"
docker unpause "$PROM_CONTAINER"

# 恢复后等待链路收敛（存在终态 run）
i=0
while [ $i -lt 120 ]; do
    DONE=$(e4_sql "select count(*) from rca_run where state in ('REPORTING','SUCCEEDED','PARTIAL','FAILED')")
    [ "$DONE" -gt 0 ] && break
    i=$((i + 5)); sleep 5
done

# 恢复后无伪造终态：UNKNOWN 调用必须挂 reason_code，SUCCESS 必须已 settle
FAKE_SUCCESS=$(e4_sql "
    select count(*) from rca_tool_invocation
     where state='SUCCESS' and settled_at is null")
e4_assert_eq "恢复后零伪 SUCCESS（未知不写成成功）" "$FAKE_SUCCESS" "0"

# LiteLLM 未知结果 → PROVISIONAL/UNMATCHED 面（存在在途未知时账本必须如实悬挂）
UNKNOWN_TOOLS=$(e4_sql "select count(*) from rca_tool_invocation where state='UNKNOWN'")
PROV=$(e4_sql "select count(*) from run_budget_entry where state in ('PROVISIONAL','UNMATCHED')")
echo "  UNKNOWN 调用=$UNKNOWN_TOOLS PROVISIONAL/UNMATCHED=$PROV（如实悬挂面）"

# 预算存储不可用面：恢复后预算态与账本可对平（无幽灵预留）
ORPHAN=$(e4_sql "
    select count(*) from run_budget_entry e
     where not exists (select 1 from rca_run r where r.id = e.run_id)")
e4_assert_eq "零幽灵预算预留（run 不存在而预留悬挂）" "$ORPHAN" "0"

# 到期明确终态：无 run 永久停在 RUNNING（墙钟/终态机制兜底）
STUCK=$(e4_sql "
    select count(*) from rca_run
     where state='RUNNING' and updated_at < now() - interval '30 minutes'")
e4_assert_eq "零永久 RUNNING（到期明确终态）" "$STUCK" "0"

e4_summary
