#!/bin/sh
# ============================================================================
# e2e-m4-08-replay.sh —— E2E-M4-08：精确回放对拍（M4-32/33 随件交付，REPLAY_ONLY）
#
# 场景（AM4 技术方案 §15.3）：同一冻结 Snapshot 连续回放两次 → 相同输入得到相同
# 语义结果（DAG/Evidence/Claim digest 一致；事件 ID/时间戳等非语义字段豁免）；
# 再逐项改变 tool/version/args/scope/time/snapshot → 任一变化即 REPLAY_MISS，
# 不得回退实时查询。
#
# 执行形态：Testcontainers IT（Am4E2E08ReplayIT）——技术方案 §15.4 明证
# E2E-M4-08 不在"必须在 195 部署栈执行"清单（business_entry=REPLAY_ONLY）；
# 真 PG 全链两轮对拍 + 扰动矩阵 + CountingExecutor 零实时回退行为取证。
# 语义矩阵穷举在 ReplayToolGatewayTest（UT）；本脚本为布局内的执行入口。
#
# 用法：docker 可用环境执行（本机无 docker 自跳；195 或任一 docker 宿主可跑）
#   sh e2e-m4-08-replay.sh [control-app 目录，缺省推导仓库内相对路径]
# ============================================================================

set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
APP="$1"
[ -n "$APP" ] || APP="$REPO/control-app"

echo "[E2E-M4-08] mvn verify -Dit.test=Am4E2E08ReplayIT（Testcontainers 真 PG）"
cd "$APP"
RC=0
if [ -n "${E4_RUN_DIR:-}" ]; then
    # runall 批次内：完整输出（含两轮语义 digest 对拍面）归档 raw/（M4-33 随件）
    mkdir -p "$E4_RUN_DIR/raw"
    LOG="$E4_RUN_DIR/raw/e2e-m4-08-replay.log"
    mvn verify -DskipITs=false -Dit.test=Am4E2E08ReplayIT \
        -Dsurefire.failIfNoSpecifiedTests=false > "$LOG" 2>&1 || RC=$?
    cat "$LOG"
else
    mvn verify -DskipITs=false -Dit.test=Am4E2E08ReplayIT \
        -Dsurefire.failIfNoSpecifiedTests=false || RC=$?
fi
[ "$RC" -eq 0 ] || { echo "[E2E-M4-08] FAIL（IT 非零退出 rc=$RC）"; exit "$RC"; }

echo "[E2E-M4-08] PASS（两轮全命中语义一致 + 扰动全 MISS + 零回退零账本污染）"
