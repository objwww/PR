set -e
L=/opt/projects/pr_agent_it/m6-m602-verify.log
echo '== 各模块 Tests run 汇总行（不含 -- in 的为模块小计）=='
grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$L" | tail -14
echo '== reactor 汇总 =='
grep -A 12 'Reactor Summary' "$L" | head -16
echo '== 构建总耗时 =='
grep -E 'Total time' "$L" | tail -1
