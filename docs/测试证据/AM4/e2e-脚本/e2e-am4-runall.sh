#!/bin/sh
# ============================================================================
# e2e-am4-runall.sh —— AM4 E2E 总 runner（落码方案附录 §一：顺序执行，任一失败
# 即非零退出；缺失或 SKIP 的场景一律总体失败——"无 skip"硬纪律）
#
# 当前状态（诚实标注）：00~07/09/10/11 场景脚本全部就绪（断言面 SQL 已对齐
# V13/V16/V17/V9 列名与冻结枚举），均未经 195 真栈执行验证——注入编排与轮询
# 参数需在 195 上迭代；m4-08（REPLAY_ONLY）= Am4E2E08ReplayIT（Testcontainers），
# 由 e2e-m4-08-replay.sh 独立执行，不在本 runner 内。
# ============================================================================

set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
FAILED=0

run_scenario() {
    script="$1"
    if [ -f "$HERE/$script" ]; then
        echo "==== [AM4-E2E] $script ===="
        if ! sh "$HERE/$script"; then
            echo "==== [AM4-E2E] $script FAIL ===="
            FAILED=1
        fi
    else
        echo "==== [AM4-E2E] $script 缺失（无 skip 纪律 → 总体失败）===="
        FAILED=1
    fi
}

run_scenario "e2e-m4-00-normal.sh"
run_scenario "e2e-m4-01-f1.sh"
run_scenario "e2e-m4-02-f2.sh"
run_scenario "e2e-m4-03-f3.sh"
run_scenario "e2e-m4-04-f4.sh"
run_scenario "e2e-m4-05-generation.sh"
run_scenario "e2e-m4-07-injection.sh"
run_scenario "e2e-m4-09-shadow.sh"
run_scenario "e2e-m4-10-budget.sh"
run_scenario "e2e-m4-11-source-outage.sh"

if [ "$FAILED" -ne 0 ]; then
    echo "[AM4-E2E] runall FAIL（存在失败或缺失场景）"
    exit 1
fi
echo "[AM4-E2E] runall PASS（00~11 全部执行且通过；08 由 IT 门独立覆盖）"
