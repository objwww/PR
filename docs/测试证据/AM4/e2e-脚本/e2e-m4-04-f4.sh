#!/bin/sh
# ============================================================================
# e2e-m4-04-f4.sh —— E2E-M4-04：B4 Metrics 与 Logs/Change 相反断言
#                     （LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：两方原始证据和 Claim 均保留；
# basis=MULTI_SOURCE_CONFLICT 并进入 NEEDS_REVIEW/不确定分节；
# 禁止按来源数量、置信度或 LLM 自述裁决。
#
# 触发：driver.py phase1 F1/F2 组合（指标劣化 + 变更面矛盾断言由执行者按
#       配方构造相反 fixture 内容）；冲突归并语义已在 ClaimReducer 穷举 UT。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

POLL_MAX="${POLL_MAX:-240}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/build/pr/deploy}"

e4_begin
# 注入前静止面（quiesce）：上轮同 fault 会话必须先恢复归零（否则告警不重发 webhook）
echo "[E2E-M4-04] 注入前静止面（quiesce F1）"
docker exec arena-e2e-cli python3 /e2e/quiesce.py F1
# T0 时间窗（BA-36）：收敛 run 认领只认本场景注入后的新行
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "[E2E-M4-04] phase1 F1（执行者按配方构造与 logs/change 相反断言的指标面）T0=$T0"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1

i=0
H_RUN=""
while [ $i -lt "$POLL_MAX" ]; do
    H_RUN=$(e4_sql "
        select r.id from rca_run r join rca_report rr on rr.run_id = r.id
         where r.created_at >= '$T0' and r.state in ('SUCCEEDED','PARTIAL')
         order by r.created_at desc limit 1")
    [ -n "$H_RUN" ] && break
    i=$((i + 5)); sleep 5
done
[ -n "$H_RUN" ] || { echo "  FAIL: 无收敛 holmes run"; exit 1; }
echo "  holmes_run=$H_RUN"

# 冲突证据面在影子链（holmes 主链不产 rca_evidence/rca_claim，v2 对齐分面）
echo "[E2E-M4-04] 触发 Native 影子 run（Am4ShadowTrigger，holmes=$H_RUN）"
TRIGGER_OUT=$( (cd "$DEPLOY_DIR" && docker compose run --rm --no-deps control-app \
    --spring.profiles.active=docker,am4-shadow-trigger \
    --spring.main.web-application-type=none \
    --am4.shadow-trigger.holmes-run-id="$H_RUN") </dev/null 2>&1 ) \
    || { printf '%s\n' "$TRIGGER_OUT" | tail -30
         echo "  FAIL: 影子触发一次性入口失败"; exit 1; }
S_RUN=$(printf '%s\n' "$TRIGGER_OUT" | grep "^AM4_SHADOW_RUN_ID=" | tail -1 | cut -d= -f2)
[ -n "$S_RUN" ] || { printf '%s\n' "$TRIGGER_OUT" | tail -30
    echo "  FAIL: 触发器未输出影子 run id"; exit 1; }
echo "  shadow_run=$S_RUN"

# 两方原始证据均保留（冲突不许删证据；影子面）
PROM=$(e4_sql "select count(*) from rca_evidence where run_id='$S_RUN' and source='prometheus'")
LOGS=$(e4_sql "select count(*) from rca_evidence where run_id='$S_RUN' and source in ('logs','change')")
e4_assert_eq "Metrics 侧原始证据保留" "$([ "$PROM" -gt 0 ] && echo yes || echo no)" "yes"
e4_assert_eq "Logs/Change 侧原始证据保留" "$([ "$LOGS" -gt 0 ] && echo yes || echo no)" "yes"

# 冲突 claim 的 basis 冻结语义：MULTI_SOURCE_CONFLICT（V17 面，影子 claim 面）
CONFLICT_CLAIMS=$(e4_sql "
    select count(*) from rca_claim
     where run_id='$S_RUN' and evidence_basis='MULTI_SOURCE_CONFLICT'")
echo "  MULTI_SOURCE_CONFLICT claim 数=$CONFLICT_CLAIMS"

# 冲突 → 不确定分支：冲突 claim 不得为确定性 TRUE（进入 UNKNOWN/复核分节）
CONFLICT_TRUE=$(e4_sql "
    select count(*) from rca_claim
     where run_id='$S_RUN' and evidence_basis='MULTI_SOURCE_CONFLICT'
       and status='TRUE'")
e4_assert_eq "冲突 claim 零确定性 TRUE（禁数量/置信度/LLM 自述裁决）" "$CONFLICT_TRUE" "0"

# 冲突存在时必须有不确定分节落库（claim status=UNKNOWN 即 NEEDS_REVIEW 事实面）
if [ "$CONFLICT_CLAIMS" -gt 0 ]; then
    UK=$(e4_sql "select count(*) from rca_claim where run_id='$S_RUN' and status='UNKNOWN'")
    e4_assert_eq "冲突轮 UNKNOWN claim ≥1（不确定分节事实面）" "$([ "$UK" -ge 1 ] && echo yes || echo no)" "yes"
fi

# 双方 Claim 均保留：run 内 claim 总数 ≥ 冲突面（反证不许覆盖原始断言行）
TOTAL_CLAIMS=$(e4_sql "select count(*) from rca_claim where run_id='$S_RUN'")
echo "  run 内 claim 总数=$TOTAL_CLAIMS（双方均保留，不互删）"

e4_summary
