#!/bin/sh
# ============================================================================
# e2e-am6-01-canary-route.sh —— E2E-AM6-01：1% Canary 路由机制 + NATIVE 全链
#                                （M6-01 案④；[195] 部署段真栈）
#
# 必断言（AM6 落码方案 §12.3 E2E-AM6-01 + 拆解验收）：
#   ① 前置姿态：nativeReady=true（两键翻转属显式操作员步骤，先备份后变更；
#     缺件态证据由 E2E-AM6-00 phase1 not-ready 轮承担）；
#   ② 1% bundle 发布/激活：canary{percent=1, whitelist[白名单直达键],
#     max_native_runs=50} + native.proposal（am4-plan.v1 三调查任务零边）；
#   ③ 固定向量注入 5 键（非白名单）：决策 ∈ {BUCKETED_HOLMES, BUCKETED_NATIVE}
#     且 engine 与决策一致、桶位/键随 run 落库；
#   ④ 独立实现对拍：python3 纯实现 murmur3_x86_32(seed=0)（参考向量
#     hash("")=0 / hash("hello")=613153351 先自证）重算全部 stickiness_key 桶位
#     == canary_bucket（交叉验证，非同源复读）；
#   ⑤ 白名单直达：whitelist 精确匹配 incidentKey → WHITELISTED + engine=NATIVE
#     → 真栈全链（worker 领取 NATIVE_INVESTIGATE → 提案编译 → DAG 三任务 →
#     冻结快照 → 报告 → 发布 → 外发）直至 SUCCEEDED；
#   ⑥ 黏性"只进不出"：同键 resolve→复燃（新 episode）→ 两行审计同桶 ==
#     重算桶位（配置可变桶位不可变）；
#   ⑦ 立即回退：rollback → 新 run 全 HOLMES（BUCKETED_HOLMES, percent=0），
#     在途/历史 NATIVE run 的 config_digest 不变（Run 启动固定不再变）；
#   ⑧ 晋升窗排除实证：canary_evidence_sample 全零 + canary_window_verdict
#     零 LIVE_CANARY（M6-01 无采集适配器=装配事实；DRILL/LIVE 排除语义已由
#     IT 案③ CanaryWindowPromotionIT 钉死，本段真栈实证零 LIVE 样本）。
#
# 用法（195 部署段）：sh e2e-am6-01-canary-route.sh（AM6_* env 见 common 头注）
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am6-common.sh"

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-01"
mkdir -p "$RUNS"
# stickiness/身份键统一小写（CanaryBucketer.normalizedKey = trim + Locale.ROOT 小写，
# DB 存的是全小写化键；incident_key/白名单匹配面保留注入原文大小写）
SFX="$(echo "${SUITE}" | tr 'A-Z' 'a-z')"

am6_log "E2E-AM6-01 开始 suite=$SUITE runs=$RUNS"
am6_resource_snapshot "$RUNS" "start"

command -v python3 >/dev/null 2>&1 \
    || am6_fail "python3 不可用（独立实现对拍为本场景核心断言，无降级面）"

# ---------------------------------------------------------------------------
# phase0 前置姿态 + 1% bundle 发布/激活（激活前留档 D_PREV 作回滚靶）
# ---------------------------------------------------------------------------
am6_log "phase0 前置：nativeReady=true + 发布 1% bundle"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status.json" >/dev/null
grep -q '"nativeReady":true' "$RUNS/status.json" \
    || am6_fail "phase0 前置不满足：nativeReady 非 true（先做两键翻转+control-app 重建，见 runbook）"
D_PREV="$(am6_active_digest "$RUNS")"
am6_log "  激活前指针（回滚靶）D_PREV=${D_PREV:-<从未激活>}"

cat > "$RUNS/bundle-canary1.content" <<'EOF'
{"policy_version":"am6-e2e-01","canary":{"percent":1,"whitelist":["alertname=HighErrorRate|service=am6e2e01-native"],"max_native_runs":50},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
D2="$(am6_publish_bundle "$RUNS/bundle-canary1.content" canary1)"
am6_activate "$D2" "$RUNS"
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$D2" ] || am6_fail "phase0 激活后 active=$_ad 期望 $D2"
am6_log "  1% bundle digest=$D2"

# ---------------------------------------------------------------------------
# phase1 固定向量注入 5 键（决策/engine/桶位对齐）
# ---------------------------------------------------------------------------
am6_log "phase1 固定向量注入 5 键（非白名单，percent=1）"
_VKEYS=""
for _i in 1 2 3 4 5; do
    _svc="am6e2e01-s${_i}-${SFX}"
    _raw="alertname=higherrorrate|service=${_svc}"
    # 库面 stickiness_key = normalizedKey(groupId,id) = "K:K" 双段
    _key="${_raw}:${_raw}"
    _VKEYS="'${_key}',${_VKEYS}"
    _code="$(am6_inject_alert HighErrorRate "$_svc" firing "$RUNS")"
    [ "$_code" = "202" ] || am6_fail "phase1 注入 $_svc 期望 202 实得 $_code: $(cat "$RUNS/alert-${_svc}-firing.resp")"
    am6_db_poll_ge "phase1 $_svc 审计行" 120 AM6_PG_URL \
        "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${_key}'
         AND bundle_digest='${D2}'" 1
    am6_psql_ro AM6_PG_URL "SELECT d.stickiness_key||'|'||coalesce(d.canary_bucket::text,'')
        ||'|'||d.decision FROM canary_route_decision d
        WHERE d.stickiness_key='${_key}' AND d.bundle_digest='${D2}'
        ORDER BY d.id DESC LIMIT 1" '-At' >> "$RUNS/vector.tsv"
done
_VKEYS="${_VKEYS%,}"
_vecrows="$(wc -l < "$RUNS/vector.tsv" | tr -d ' ')"
[ "$_vecrows" = "5" ] || am6_fail "phase1 向量行数=$_vecrows 期望 5: $(cat "$RUNS/vector.tsv")"
am6_db_poll_ge "phase1 决策/engine/桶位五键全对齐" 30 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
     WHERE d.bundle_digest='${D2}' AND d.stickiness_key IN (${_VKEYS})
       AND d.decision IN ('BUCKETED_HOLMES','BUCKETED_NATIVE')
       AND ((d.decision='BUCKETED_NATIVE' AND r.engine='NATIVE')
            OR (d.decision='BUCKETED_HOLMES' AND r.engine='HOLMES'))
       AND r.canary_bucket=d.canary_bucket AND r.stickiness_key=d.stickiness_key" 5
am6_log "phase1 PASS（5/5 决策∈{BUCKETED_HOLMES,BUCKETED_NATIVE} 且 engine 对齐）"

# ---------------------------------------------------------------------------
# phase2 murmur3 独立实现对拍（python3 纯实现；参考向量先自证）
# ---------------------------------------------------------------------------
am6_log "phase2 murmur3 独立实现对拍（vector.tsv 共 $_vecrows 键）"
cat > "$RUNS/murmur3.py" <<'PY'
import sys

def murmur3_32(data):
    h = 0
    n = len(data) // 4
    for i in range(n):
        j = i * 4
        k = int.from_bytes(data[j:j+4], 'little')
        k = (k * 0xcc9e2d51) & 0xFFFFFFFF
        k = ((k << 15) | (k >> 17)) & 0xFFFFFFFF
        k = (k * 0x1b873593) & 0xFFFFFFFF
        h ^= k
        h = ((h << 13) | (h >> 19)) & 0xFFFFFFFF
        h = (h * 5 + 0xe6546b64) & 0xFFFFFFFF
    k = 0
    tail = n * 4
    r = len(data) & 3
    if r == 3:
        k ^= data[tail + 2] << 16
    if r >= 2:
        k ^= data[tail + 1] << 8
    if r >= 1:
        k ^= data[tail]
        k = (k * 0xcc9e2d51) & 0xFFFFFFFF
        k = ((k << 15) | (k >> 17)) & 0xFFFFFFFF
        k = (k * 0x1b873593) & 0xFFFFFFFF
        h ^= k
    h ^= len(data)
    h ^= h >> 16
    h = (h * 0x85ebca6b) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 0xc2b2ae35) & 0xFFFFFFFF
    h ^= h >> 16
    return h

def bucket(key, total=100):
    norm = key.strip().lower()
    return (murmur3_32(norm.encode('utf-8')) * total) >> 32

if __name__ == '__main__':
    assert murmur3_32(b'') == 0, '参考向量 hash("")=0'
    assert murmur3_32(b'hello') == 613153351, '参考向量 hash("hello")=613153351'
    bad = 0
    checked = 0
    for line in open(sys.argv[1], encoding='utf-8'):
        line = line.strip()
        if not line:
            continue
        key, b, dec = line.rsplit('|', 2)  # stickiness_key 内嵌 '|'（incidentKey 形），从右起拆
        expect = bucket(key)
        ok = (str(expect) == b)
        checked += 1
        print(('OK   ' if ok else 'FAIL ') + 'db_bucket=' + b
              + ' recomputed=' + str(expect) + ' decision=' + dec + ' key=' + key)
        if not ok:
            bad += 1
    print('SUMMARY checked=%d bad=%d' % (checked, bad))
    sys.exit(1 if bad else 0)
PY
python3 "$RUNS/murmur3.py" "$RUNS/vector.tsv" > "$RUNS/murmur-check.txt" \
    || am6_fail "phase2 独立实现对拍不一致: $(cat "$RUNS/murmur-check.txt")"
am6_log "phase2 PASS（$RUNS/murmur-check.txt）"

# ---------------------------------------------------------------------------
# phase3 白名单直达 → NATIVE 真栈全链（worker 驱动至 SUCCEEDED）
# ---------------------------------------------------------------------------
am6_log "phase3 白名单直达全链（WHITELISTED → NATIVE → SUCCEEDED）"
WL_RAW="alertname=higherrorrate|service=am6e2e01-native"
WLKEY_DB="${WL_RAW}:${WL_RAW}"
_code="$(am6_inject_alert HighErrorRate "am6e2e01-native" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase3 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e01-native-firing.resp")"
am6_db_poll_ge "phase3 WHITELISTED 审计行" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY_DB}'
     AND decision='WHITELISTED' AND bundle_digest='${D2}'" 1
NRUNID="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${WLKEY_DB}' AND decision='WHITELISTED' AND bundle_digest='${D2}'
    ORDER BY id DESC LIMIT 1" '-At')"
am6_log "  NATIVE run=$NRUNID（真栈 worker 驱动，超时 300s）"
am6_db_poll_ge "phase3 run SUCCEEDED(engine=NATIVE,digest=$D2)" 300 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${NRUNID}' AND state='SUCCEEDED'
     AND engine='NATIVE' AND config_digest='${D2}'" 1
am6_db_poll_ge "phase3 rca_report 含 NATIVE 标识" 30 AM6_PG_URL \
    "SELECT count(*) FROM rca_report WHERE run_id='${NRUNID}'
     AND raw_text LIKE '%NATIVE%'" 1
am6_db_poll_ge "phase3 report_publication 落行" 30 AM6_PG_URL \
    "SELECT count(*) FROM report_publication p JOIN rca_report rr ON p.report_id=rr.id
     WHERE rr.run_id='${NRUNID}'" 1
am6_db_poll_ge "phase3 notify_outbox 落行" 30 AM6_PG_URL \
    "SELECT count(*) FROM notify_outbox WHERE report_id IN
     (SELECT id FROM rca_report WHERE run_id='${NRUNID}')" 1
am6_db_poll_ge "phase3 NATIVE_INVESTIGATE 驱动任务 DONE" 30 AM6_PG_URL \
    "SELECT count(*) FROM rca_task WHERE run_id='${NRUNID}'
     AND task_key='NATIVE_INVESTIGATE' AND state='DONE'" 1
am6_psql_ro AM6_PG_URL "SELECT task_key||'|'||state FROM rca_task
    WHERE run_id='${NRUNID}' AND task_key LIKE 'investigate-%' ORDER BY task_key" \
    '-At' > "$RUNS/phase3-dag-states.txt"
[ "$(wc -l < "$RUNS/phase3-dag-states.txt" | tr -d ' ')" -ge 3 ] \
    || am6_fail "phase3 DAG 任务应 ≥3: $(cat "$RUNS/phase3-dag-states.txt")"
_dagbad="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${NRUNID}' AND task_key LIKE 'investigate-%'
    AND state NOT IN ('DONE','DEAD')" '-At')"
[ "$_dagbad" = "0" ] || am6_fail "phase3 DAG 任务存在非终态: $(cat "$RUNS/phase3-dag-states.txt")"
am6_log "phase3 PASS（全链九断言齐：决策/引擎终态/报告/发布/外发/驱动任务/DAG 终态；replay MISS 降级 DEAD 不阻断）"

# ---------------------------------------------------------------------------
# phase4 黏性"只进不出"：resolve → 复燃 → 同键两行同桶 == 重算
# ---------------------------------------------------------------------------
am6_log "phase4 黏性只进不出（s3 resolve → 复燃）"
S3_RAW="alertname=HighErrorRate|service=am6e2e01-s3-${SFX}"
S3_L="alertname=higherrorrate|service=am6e2e01-s3-${SFX}"
S3_DB="${S3_L}:${S3_L}"
# 前置：s3 的 gen0 run 必须已终态——复燃铸造前置 = 无活跃 run 残留（castRunIfFree
# 规格行为：有活跃 run 的复燃只记事件不铸 run；LLM 时延波动下不等待会漏铸）
am6_db_poll_ge "phase4 前置 s3 gen0 run 终态" 300 AM6_PG_URL \
    "SELECT count(*) FROM rca_run r JOIN incident i ON i.id=r.incident_id
     WHERE i.incident_key='${S3_RAW}' AND r.state NOT IN ('QUEUED','RUNNING','REPORTING')" 1
_code="$(am6_inject_alert HighErrorRate "am6e2e01-s3-${SFX}" resolved "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase4 resolve 注入期望 202 实得 $_code"
am6_db_poll_ge "phase4 s3 incident RESOLVED" 120 AM6_PG_URL \
    "SELECT count(*) FROM incident WHERE incident_key='${S3_RAW}' AND status='RESOLVED'" 1
sleep 2  # refire startsAt 必须严格晚于 resolvedAt（乱序防御判据；毫秒戳+间隔双保险）
_code="$(am6_inject_alert HighErrorRate "am6e2e01-s3-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase4 复燃注入期望 202 实得 $_code"
am6_db_poll_ge "phase4 s3 复燃第二行审计" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${S3_DB}'
     AND bundle_digest='${D2}'" 2
B1="$(am6_psql_ro AM6_PG_URL "SELECT canary_bucket FROM canary_route_decision
    WHERE stickiness_key='${S3_DB}' AND bundle_digest='${D2}' ORDER BY id ASC LIMIT 1" '-At')"
B2="$(am6_psql_ro AM6_PG_URL "SELECT canary_bucket FROM canary_route_decision
    WHERE stickiness_key='${S3_DB}' AND bundle_digest='${D2}' ORDER BY id DESC LIMIT 1" '-At')"
[ -n "$B1" ] && [ "$B1" = "$B2" ] || am6_fail "phase4 同键跨 episode 桶位漂移: b1=$B1 b2=$B2"
printf '%s|%s|refire\n' "$S3_DB" "$B2" > "$RUNS/vector-refire.tsv"
python3 "$RUNS/murmur3.py" "$RUNS/vector-refire.tsv" >> "$RUNS/murmur-check.txt" \
    || am6_fail "phase4 复燃桶位与独立实现不一致"
am6_log "phase4 PASS（同键两行同桶 b1=b2=$B1，且==murmur3 重算）"

# ---------------------------------------------------------------------------
# phase5 立即回退：rollback → 新 run 全 HOLMES；在途 digest 不变
# ---------------------------------------------------------------------------
am6_log "phase5 回退：rollback → 新 run 全 HOLMES + 历史 NATIVE digest 不变"
# 回滚靶独立发布（policy_version 带 suite → 内容必异于 D2）——上一轮失败可能把
# 指针留在 D2，此时 D_PREV==D2 退化场景下"回滚到 D_PREV" = 原地踏步（percent 恒 1）
printf '{"policy_version":"am6-e2e-01-rollback-%s","canary":{"percent":0}}' "$SUITE" \
    > "$RUNS/bundle-rollback.content"
DRB="$(am6_publish_bundle "$RUNS/bundle-rollback.content" rollback)"
am6_log "  回滚靶 digest=$DRB（回滚前指针 D_PREV=${D_PREV:-<从未激活>}）"
am6_rollback "$DRB" "$RUNS"
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$DRB" ] || am6_fail "phase5 回滚后 active=$_ad 期望 $DRB"
POST_L="alertname=higherrorrate|service=am6e2e01-post-${SFX}"
POSTKEY="${POST_L}:${POST_L}"
_code="$(am6_inject_alert HighErrorRate "am6e2e01-post-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase5 注入期望 202 实得 $_code"
am6_db_poll_ge "phase5 回滚后新 run 全 HOLMES" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
     WHERE d.stickiness_key='${POSTKEY}' AND d.bundle_digest='${DRB}'
       AND d.decision='BUCKETED_HOLMES' AND d.percent=0 AND r.engine='HOLMES'" 1
_same="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_run WHERE id='${NRUNID}'
    AND engine='NATIVE' AND config_digest='${D2}'" '-At')"
[ "$_same" = "1" ] || am6_fail "phase5 历史 NATIVE run config_digest 应不变（$D2）"
am6_log "phase5 PASS（回滚只影响新 Run；phase3 NATIVE run=$NRUNID digest 固定 $D2）"

# ---------------------------------------------------------------------------
# phase6 晋升窗排除实证（零 LIVE 样本/零 LIVE 判定）
# ---------------------------------------------------------------------------
am6_log "phase6 晋升窗排除实证（M6-01 无采集适配器=装配事实）"
_samp="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM canary_evidence_sample" '-At')"
[ "$_samp" = "0" ] || am6_fail "phase6 canary_evidence_sample 应全零（无采集面）实得 $_samp"
_live="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM canary_window_verdict
    WHERE evidence_class='LIVE_CANARY'" '-At')"
[ "$_live" = "0" ] || am6_fail "phase6 LIVE_CANARY 窗判定应零实得 $_live"
am6_log "phase6 PASS（零样本/零 LIVE 判定；DRILL 不占晋升链语义=IT 案③ CanaryWindowPromotionIT 钉死）"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-01" \
    "1% Canary 路由机制（白名单直进全链/murmur3 对拍/黏性只进不出/立即回退/晋升窗排除）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-01 PASS（suite=$SUITE，证据=$RUNS）"
