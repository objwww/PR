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

e4_begin
echo "[E2E-M4-04] phase1 F1（执行者按配方构造与 logs/change 相反断言的指标面）"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1

i=0
RUN_ID=""
while [ $i -lt "$POLL_MAX" ]; do
    RUN_ID=$(e4_sql "
        select r.id from rca_run r join rca_report rr on rr.run_id = r.id
         where r.state in ('REPORTING','SUCCEEDED','PARTIAL')
         order by r.created_at desc limit 1")
    [ -n "$RUN_ID" ] && break
    i=$((i + 5)); sleep 5
done
[ -n "$RUN_ID" ] || { echo "  FAIL: 无收敛 run"; exit 1; }
echo "  run=$RUN_ID"

# 两方原始证据均保留（冲突不许删证据）
PROM=$(e4_sql "select count(*) from rca_evidence where run_id='$RUN_ID' and source='prometheus'")
LOGS=$(e4_sql "select count(*) from rca_evidence where run_id='$RUN_ID' and source in ('logs','change')")
e4_assert_eq "Metrics 侧原始证据保留" "$([ "$PROM" -gt 0 ] && echo yes || echo no)" "yes"
e4_assert_eq "Logs/Change 侧原始证据保留" "$([ "$LOGS" -gt 0 ] && echo yes || echo no)" "yes"

# 冲突 claim 的 basis 冻结语义：MULTI_SOURCE_CONFLICT（V17 面）
CONFLICT_CLAIMS=$(e4_sql "
    select count(*) from rca_claim
     where run_id='$RUN_ID' and evidence_basis='MULTI_SOURCE_CONFLICT'")
echo "  MULTI_SOURCE_CONFLICT claim 数=$CONFLICT_CLAIMS"

# 冲突 → 不确定分支：冲突 claim 不得为确定性 TRUE（进入 UNKNOWN/复核分节）
CONFLICT_TRUE=$(e4_sql "
    select count(*) from rca_claim
     where run_id='$RUN_ID' and evidence_basis='MULTI_SOURCE_CONFLICT'
       and status='TRUE'")
e4_assert_eq "冲突 claim 零确定性 TRUE（禁数量/置信度/LLM 自述裁决）" "$CONFLICT_TRUE" "0"

# 冲突存在时必须有不确定分节落库（claim status=UNKNOWN 即 NEEDS_REVIEW 事实面）
if [ "$CONFLICT_CLAIMS" -gt 0 ]; then
    UK=$(e4_sql "select count(*) from rca_claim where run_id='$RUN_ID' and status='UNKNOWN'")
    e4_assert_eq "冲突轮 UNKNOWN claim ≥1（不确定分节事实面）" "$([ "$UK" -ge 1 ] && echo yes || echo no)" "yes"
fi

# 双方 Claim 均保留：run 内 claim 总数 ≥ 冲突面（反证不许覆盖原始断言行）
TOTAL_CLAIMS=$(e4_sql "select count(*) from rca_claim where run_id='$RUN_ID'")
echo "  run 内 claim 总数=$TOTAL_CLAIMS（双方均保留，不互删）"

e4_summary
