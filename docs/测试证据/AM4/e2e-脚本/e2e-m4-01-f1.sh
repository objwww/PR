#!/bin/sh
# ============================================================================
# e2e-m4-01-f1.sh —— E2E-M4-01：B1/F1 真实订单请求触发告警跑完 Native DAG
#                     （LIVE_BUSINESS + 混合证据）
#
# 必断言（AM4 技术方案 §15.3）：同 generation；真实 Prometheus 证据 + 明示的
# Logs/Change replay 证据可回查；事件序列与 DAG 终态闭合；报告根因命中 GT
# 或诚实进入不确定分支；无证据的确定根因必须失败；Candidate 发布增量为 0。
#
# 触发：arena-e2e-cli 内 driver.py phase1 F1（受控故障注入口，CHAOS_ADMIN_TOKEN）；
#       Native 影子 run 由本脚本经《195-部署配方-v1.md》§5 方式 A 一次性入口
#       自动触发（Am4ShadowTriggerConfig，web-none 零副作用），SHADOW_RUN_ID
#       从 stdout 标记 AM4_SHADOW_RUN_ID= 捕获（亦可用环境变量显式传入复用）。
#
# v2（195 迭代修正，合并 BA-36 T0 窗）：v1 的 poll 无基线判别——脏基线（历史
# run）下首轮即命中旧 run（195 实证：抓到前日 gen13 旧 run，新 run 迟 31s 未及
# 落库）；且 holmes/shadow 两面断言混用同一 run id（holmes 面无证据行、影子面无
# 报告行，混查互假）。v2 以 T0 为水位：holmes 面（T0 后新 run）断言报告与
# generation；影子面（SHADOW_RUN_ID）断言证据/DAG/claim/零发布/同快照。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

SHADOW_RUN_ID="${SHADOW_RUN_ID:-}"
POLL_MAX="${POLL_MAX:-240}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/build/pr/deploy}"

e4_begin
# 注入前静止面（quiesce）：上轮同 fault 会话必须先恢复归零——否则告警持续
# firing、alertmanager 不重发 webhook，本轮注入不产生新 incident/run（195 实证）
echo "[E2E-M4-01] 注入前静止面（quiesce F1）"
e4_quiesce F1

# T0 时间窗（BA-36）：run/报告认领只认本场景注入后的新行，防陈旧 run 冒充本轮
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "[E2E-M4-01] phase1 F1 注入（arena-chaos-admin 受控注入口）T0=$T0"
docker exec arena-e2e-cli python3 /e2e/driver.py phase1 F1

# ---- Holmes 主链：T0 后新 run 跑完（终态 + 报告落库） ----
i=0
H_RUN=""
while [ $i -lt "$POLL_MAX" ]; do
    H_RUN=$(e4_sql "
        select r.id from rca_run r
         where r.created_at >= '$T0'
           and r.state in ('SUCCEEDED','PARTIAL')
           and exists (select 1 from rca_report rr where rr.run_id = r.id)
         order by r.created_at limit 1")
    [ -n "$H_RUN" ] && break
    i=$((i + 5)); sleep 5
done
[ -n "$H_RUN" ] || { echo "  FAIL: T0=$T0 后 run 未收敛（超时 ${POLL_MAX}s）"; exit 1; }
echo "  holmes_run=$H_RUN"

# 同 generation：holmes run 代与 incident 当前代一致
e4_assert_eq "holmes run 与 incident 同 generation" "$(e4_sql "
    select count(*) from rca_run r join incident i on i.id = r.incident_id
     where r.generation = i.generation and r.id = '$H_RUN'")" "1"

# 报告落库且验证态合法（holmes 主链面：STRUCTURE_VALIDATED 或 REJECTED_*）
REPORT_STATUS=$(e4_sql "
    select validation_status from rca_report where run_id='$H_RUN'
     order by created_at desc limit 1")
e4_assert_eq "holmes 报告验证态非空" "$([ -n "$REPORT_STATUS" ] && echo yes || echo no)" "yes"
echo "  report_status=$REPORT_STATUS（根因命中 GT 的判定归 eval 正式门禁面，非 E2E SQL 断言）"

# ---- Native 影子 run：部署配方 §5 方式 A 一次性入口触发（未显式传入时） ----
if [ -z "$SHADOW_RUN_ID" ]; then
    echo "[E2E-M4-01] 触发 Native 影子 run（Am4ShadowTrigger，holmes=$H_RUN）"
    TRIGGER_OUT=$( (cd "$DEPLOY_DIR" && docker compose run --rm --no-deps control-app \
        --spring.profiles.active=docker,am4-shadow-trigger \
        --spring.main.web-application-type=none \
        --am4.shadow-trigger.holmes-run-id="$H_RUN") </dev/null 2>&1 ) \
        || { printf '%s\n' "$TRIGGER_OUT" | tail -30
             echo "  FAIL: 影子触发一次性入口失败"; exit 1; }
    SHADOW_RUN_ID=$(printf '%s\n' "$TRIGGER_OUT" | grep "^AM4_SHADOW_RUN_ID=" \
        | tail -1 | cut -d= -f2)
    [ -n "$SHADOW_RUN_ID" ] || { printf '%s\n' "$TRIGGER_OUT" | tail -30
        echo "  FAIL: 触发器未输出影子 run id"; exit 1; }
fi
echo "  shadow_run=$SHADOW_RUN_ID"

# 影子身份镜像：同 incident 同 generation（同一 input snapshot 的代际面）
e4_assert_eq "影子 run 与 holmes 同 incident" "$(e4_sql "
    select count(*) from rca_run s join rca_run h on h.id = '$H_RUN'
     where s.id = '$SHADOW_RUN_ID' and s.incident_id = h.incident_id")" "1"
e4_assert_eq "影子 run 与 holmes 同 generation" "$(e4_sql "
    select count(*) from rca_run s join rca_run h on h.id = '$H_RUN'
     where s.id = '$SHADOW_RUN_ID' and s.generation = h.generation")" "1"

# 混合证据（影子面）：真实 Prometheus + 明示 fixture replay 均可回查
PROM_EVIDENCE=$(e4_sql "
    select count(*) from rca_evidence
     where run_id='$SHADOW_RUN_ID' and source='prometheus'")
REPLAY_EVIDENCE=$(e4_sql "
    select count(*) from rca_evidence
     where run_id='$SHADOW_RUN_ID' and source in ('logs','change')")
e4_assert_eq "Prometheus 证据存在（真实源）" "$([ "$PROM_EVIDENCE" -gt 0 ] && echo yes || echo no)" "yes"
e4_assert_eq "Logs/Change replay 证据存在（fixture 明示）" "$([ "$REPLAY_EVIDENCE" -gt 0 ] && echo yes || echo no)" "yes"

# 证据完整性：payload_digest 全部非空（五步 digest 纪律的落库面）
e4_assert_eq "证据行 payload_digest 无空" "$(e4_sql "
    select count(*) from rca_evidence
     where run_id='$SHADOW_RUN_ID' and (payload_digest is null or payload_digest='')")" "0"

# DAG 终态闭合：影子 run 无永久 BLOCKED/READY/RUNNING/LEASED 残留任务
e4_assert_eq "DAG 任务终态闭合" "$(e4_sql "
    select count(*) from rca_task where run_id='$SHADOW_RUN_ID'
      and state in ('READY','BLOCKED','RUNNING','LEASED')")" "0"

# 两路同一 input snapshot：影子证据盖章 == holmes run 的 investigation hash
SNAP_S=$(e4_sql "
    select scope::jsonb->>'input_snapshot_digest' from rca_evidence
     where run_id='$SHADOW_RUN_ID'
       and scope::jsonb->>'input_snapshot_digest' is not null limit 1")
SNAP_H=$(e4_sql "select investigation_hash from rca_run where id='$H_RUN'")
e4_assert_eq "两路同一 input snapshot（影子盖章==holmes 调查材料）" "$SNAP_S" "$SNAP_H"

# 无证据的确定根因必须失败：TRUE/FALSE claim 必须有 evidence_refs 引用
e4_assert_eq "确定 claim 均引用证据（无证据不产确认根因）" "$(e4_sql "
    select count(*) from rca_claim
     where run_id='$SHADOW_RUN_ID' and status in ('TRUE','FALSE')
       and (evidence_refs is null or evidence_refs::text = '[]')")" "0"

# Candidate 发布增量 0（影子纪律：零报告零发布落库）
e4_assert_eq "影子 run 零报告行" "$(e4_sql "
    select count(*) from rca_report where run_id='$SHADOW_RUN_ID'")" "0"
e4_assert_eq "影子 run 零发布行" "$(e4_sql "
    select count(*) from report_publication rp
     join rca_report rr on rr.id = rp.report_id
     where rr.run_id = '$SHADOW_RUN_ID'")" "0"

# 影子终态 = REPORTING（G2 套件同款终态：报告链不组装，零伪造 SUCCEEDED）
e4_assert_eq "影子 run 终于 REPORTING（零伪造 SUCCEEDED）" "$(e4_sql "
    select count(*) from rca_run where id='$SHADOW_RUN_ID' and state='REPORTING'")" "1"

e4_summary
