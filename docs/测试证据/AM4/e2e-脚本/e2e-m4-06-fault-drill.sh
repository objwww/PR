#!/bin/sh
# ============================================================================
# e2e-m4-06-fault-drill.sh —— E2E-M4-06：故障演练五提交边界（M4-38 随件交付，
# FAULT_DRILL，AM4 技术方案 §15.3）
#
# 五提交边界逐点 SIGKILL 等价注入（组件实例丢弃 + 全新实例同存储重驱；
# "中断" = 操作只执行到该边界、后续步骤不执行）：
#   ①plan 落库后重启        → 任务零重建、图同构、可推进、无永久 BLOCKED
#   ②tool PENDING+预算悬挂  → UNKNOWN/UNMATCHED 收口、零透支、零重复工具副作用
#   ③Claim 落行后重放       → UNCHANGED 幂等、零重复行、零重复事件
#   ④finish 事务前中断重启  → 恰一份报告、预算实扣 COMMITTED
#   ⑤finish 完成后重放      → 终态不可改写、零重复报告
#   对照案：扰动重驱 vs 直通 → 终局事实（任务态+边集）一致
#
# 执行形态：Am4E2E06FaultDrillIT（Testcontainers 真 PG）——§15.4：06 不在
# "必须在 195 部署栈执行"强制清单；195（docker 宿主）同样以本脚本执行。
#
# 用法：docker 可用环境执行
#   sh e2e-m4-06-fault-drill.sh [control-app 目录，缺省推导仓库内相对路径]
# ============================================================================

set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
APP="$1"
[ -n "$APP" ] || APP="$REPO/control-app"

echo "[E2E-M4-06] mvn verify -Dit.test=Am4E2E06FaultDrillIT（Testcontainers 真 PG）"
cd "$APP"
RC=0
if [ -n "${E4_RUN_DIR:-}" ]; then
    # runall 批次内：完整输出归档 raw/（附录 §一 runs/<UTC批次>/raw）
    mkdir -p "$E4_RUN_DIR/raw"
    LOG="$E4_RUN_DIR/raw/e2e-m4-06-fault-drill.log"
    mvn verify -DskipITs=false -Dit.test=Am4E2E06FaultDrillIT \
        -Dsurefire.failIfNoSpecifiedTests=false > "$LOG" 2>&1 || RC=$?
    cat "$LOG"
else
    mvn verify -DskipITs=false -Dit.test=Am4E2E06FaultDrillIT \
        -Dsurefire.failIfNoSpecifiedTests=false || RC=$?
fi
[ "$RC" -eq 0 ] || { echo "[E2E-M4-06] FAIL（IT 非零退出 rc=$RC）"; exit "$RC"; }

echo "[E2E-M4-06] PASS（五边界重驱收敛 + 零重复副作用 + 零透支 + 对照一致）"
