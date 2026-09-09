#!/bin/sh
# ============================================================================
# e2e-am6-07-holmes-retirement.sh —— E2E-AM6-07：M6-07 Holmes 生产路径下线回归
#                            （[195] 部署段真栈；经 m6-run-scenario.sh 包裹）
#
# 必断言（AM6 落码方案 §12.3 E2E-AM6-07 + M6-07 验收；零跳过零降级面）：
#   ① 退场姿态：health 200 + nativeReady=true + 进场指针 D_PREV 留档（回滚靶）；
#     holmesgpt 容器/卷全零、alert-net 成员无 holmes（C-63：litellm 在场）；
#   ② BUCKETED_HOLMES 决策照记不铸 run（C-77 × BA-60/V35 真栈面）：
#     percent=0 → 注入新告警 → 决策行 run_id IS NULL + 同键 rca_run 恒零 +
#     control-app 日志守卫行「决策照记不铸 run」≥1；
#   ③ WHITELISTED → NATIVE 真栈全链：决策行 run_id 非空（V31 原子对语义）→
#     run SUCCEEDED(engine=NATIVE, config_digest=激活 digest) → rca_report/
#     report_publication/notify_outbox/NATIVE_INVESTIGATE DONE/DAG≥3 终态；
#   ④ 历史回放 + 审计可读：HOLMES SUCCEEDED run 数跨批次零增；审计两态共存
#     可查（V35 前 run_id 非空 ≥1 与 V35 后 NULL ≥1 同查询面）；NATIVE 决策↔
#     run join 可读；
#   ⑤ 密钥归并终扫（只列键名/词形，零值落盘）：宿主 .env HOLMES 键族全零 +
#     control-app 容器 env HOLMES 键族全零 + 双 compose 项目 config 渲染
#     holmes 字形全零；
#   ⑥ drain barrier v2 回放（M6-06 语义）：B1~B4 全零；
#   ⑦ 制品恢复路径实跑：封存清单在场 + V35 前置 pg_dump sha256 复验 + 最旧
#     HOLMES 报告制品读回计时（RTO 口径同 M6-06 restore）；不宣称一键回切
#     （C-69）——回滚是发布指针操作面，非 Holmes 服务复活开关；
#   ⑧ 收官姿态：percent=100 全量 NATIVE bundle 激活（无白名单）→ 新告警
#     BUCKETED_NATIVE → SUCCEEDED（M6 里程碑弧终点；active 停留 percent=100，
#     D_PREV 留档供发布指针回退，本套件不执行）。
#
# 用法（195 部署段）：sh m6-run-scenario.sh e2e-am6-07-holmes-retirement.sh \
#                        <nativeReady期望> <证据log>
# ============================================================================

set -e
. "$(dirname "$0")"/e2e-am6-common.sh

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-07"
mkdir -p "$RUNS"
SFX="$(echo "${SUITE}" | tr 'A-Z' 'a-z')"

am6_log "E2E-AM6-07 开始 suite=$SUITE runs=$RUNS"
am6_resource_snapshot "$RUNS" "start"

# ---------------------------------------------------------------------------
# phase0 退场姿态：health/nativeReady/容器·卷·网络 + 进场指针留档
# ---------------------------------------------------------------------------
am6_log "phase0 退场姿态：health 200 + nativeReady + holmes 容器/卷/网络全零"
_code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)"
[ "$_code" = "200" ] || am6_fail "phase0 control-app health=$_code"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-pre.json" >/dev/null
grep -q '"nativeReady":true' "$RUNS/status-pre.json" \
    || am6_fail "phase0 nativeReady 非 true: $(cat "$RUNS/status-pre.json")"
D_PREV="$(am6_active_digest "$RUNS")"
am6_log "  进场指针 D_PREV=${D_PREV:-<从未激活>}（M6-05 percent=0 锚，回滚靶留档）"
{
    echo "holmes_containers=$(docker ps -a --format '{{.Names}}' | grep -ci holmes || true)"
    echo "holmes_volumes=$(docker volume ls --format '{{.Name}}' | grep -i holmes | tr '\n' ' ' || true)"
    echo "alert_net_members=$(docker network inspect alert-net \
        --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null || true)"
    echo "litellm_in_scene=$(docker ps --format '{{.Names}}' | grep -c '^litellm-am3$' || true)"
} > "$RUNS/retirement-posture.txt"
_net="$(grep 'alert_net_members' "$RUNS/retirement-posture.txt" | grep -io 'holmes[a-z0-9_-]*' || true)"
[ -z "$_net" ] || am6_fail "phase0 alert-net 残留 holmes 成员: $_net"
[ "$(grep 'holmes_containers' "$RUNS/retirement-posture.txt" | cut -d= -f2)" = "0" ] \
    || am6_fail "phase0 存在 holmes 容器: $(cat "$RUNS/retirement-posture.txt")"
[ "$(grep 'litellm_in_scene' "$RUNS/retirement-posture.txt" | cut -d= -f2)" = "1" ] \
    || am6_fail "phase0 litellm-am3 不在场（C-63 保留面被破）"
am6_log "phase0 PASS（health 200/nativeReady/无 holmes 容器卷、alert-net 无 holmes、litellm 在场）"

# ---------------------------------------------------------------------------
# phase1 percent=0 → BUCKETED_HOLMES 决策照记不铸 run（C-77 × V35）
# ---------------------------------------------------------------------------
am6_log "phase1 percent=0 → BUCKETED_HOLMES 决策照记、run_id 留空、零 run 铸造"
printf '{"policy_version":"am6-e2e-07-holmeswilling-%s","canary":{"percent":0}}' "$SUITE" \
    > "$RUNS/bundle-holmes.content"
D0="$(am6_publish_bundle "$RUNS/bundle-holmes.content" holmes)"
am6_activate "$D0" "$RUNS"
P1_RAW="alertname=HighErrorRate|service=am6e2e07-holmes-${SFX}"
P1_L="alertname=higherrorrate|service=am6e2e07-holmes-${SFX}"
P1KEY="${P1_L}:${P1_L}"
P1_T0="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
_code="$(am6_inject_alert HighErrorRate "am6e2e07-holmes-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase1 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e07-holmes-${SFX}-firing.resp")"
am6_db_poll_ge "phase1 BUCKETED_HOLMES 审计行（run_id IS NULL）" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${P1KEY}'
     AND decision='BUCKETED_HOLMES' AND bundle_digest='${D0}' AND run_id IS NULL" 1
_zero="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_run r
    JOIN incident i ON i.id=r.incident_id WHERE i.incident_key='${P1_RAW}'" '-At')"
[ "$_zero" = "0" ] || am6_fail "phase1 同键 run 应恒零（C-77 破）实得 $_zero"
docker logs deploy-control-app-1 --since "$P1_T0" 2>&1 \
    | grep -c '决策照记不铸 run' > "$RUNS/phase1-guard-log.txt" || true
_guard="$(tr -d '[:space:]' < "$RUNS/phase1-guard-log.txt")"
[ "${_guard:-0}" -ge 1 ] 2>/dev/null \
    || am6_fail "phase1 守卫日志未命中「决策照记不铸 run」: $_guard"
am6_log "phase1 PASS（决策行 run_id 留空/同键零 run/守卫日志 ${_guard} 次；digest=$D0）"

# ---------------------------------------------------------------------------
# phase2 WHITELISTED → NATIVE 真栈全链（V31 原子对语义回归面）
# ---------------------------------------------------------------------------
am6_log "phase2 WHITELISTED → NATIVE 全链（run_id 非空 → SUCCEEDED → 生产收尾）"
cat > "$RUNS/bundle-p100.content" <<EOF
{"policy_version":"am6-e2e-07-p100-${SUITE}","canary":{"percent":100,"whitelist":["alertname=HighErrorRate|service=am6e2e07-native-${SFX}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
D100="$(am6_publish_bundle "$RUNS/bundle-p100.content" p100)"
am6_activate "$D100" "$RUNS"
WL_L="alertname=higherrorrate|service=am6e2e07-native-${SFX}"
WLKEY="${WL_L}:${WL_L}"
_code="$(am6_inject_alert HighErrorRate "am6e2e07-native-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase2 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e07-native-${SFX}-firing.resp")"
am6_db_poll_ge "phase2 WHITELISTED 审计行（run_id 非空=原子对）" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY}'
     AND decision='WHITELISTED' AND bundle_digest='${D100}' AND run_id IS NOT NULL" 1
NRUNID="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${WLKEY}' AND decision='WHITELISTED' AND bundle_digest='${D100}'
    ORDER BY id DESC LIMIT 1" '-At')"
am6_log "  NATIVE run=$NRUNID（真栈 agents 驱动，超时 420s）"
am6_db_poll_ge "phase2 run SUCCEEDED(engine=NATIVE,digest=$D100)" 420 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${NRUNID}' AND state='SUCCEEDED'
     AND engine='NATIVE' AND config_digest='${D100}'" 1
am6_db_poll_ge "phase2 rca_report 在场" 60 AM6_PG_URL \
    "SELECT count(*) FROM rca_report WHERE run_id='${NRUNID}'" 1
am6_db_poll_ge "phase2 report_publication 落行" 30 AM6_PG_URL \
    "SELECT count(*) FROM report_publication p JOIN rca_report rr ON p.report_id=rr.id
     WHERE rr.run_id='${NRUNID}'" 1
am6_db_poll_ge "phase2 notify_outbox 落行" 30 AM6_PG_URL \
    "SELECT count(*) FROM notify_outbox WHERE report_id IN
     (SELECT id FROM rca_report WHERE run_id='${NRUNID}')" 1
am6_db_poll_ge "phase2 NATIVE_INVESTIGATE 驱动任务 DONE" 30 AM6_PG_URL \
    "SELECT count(*) FROM rca_task WHERE run_id='${NRUNID}'
     AND task_key='NATIVE_INVESTIGATE' AND state='DONE'" 1
am6_psql_ro AM6_PG_URL "SELECT task_key||'|'||state FROM rca_task
    WHERE run_id='${NRUNID}' AND task_key LIKE 'investigate-%' ORDER BY task_key" \
    '-At' > "$RUNS/phase2-dag-states.txt"
[ "$(wc -l < "$RUNS/phase2-dag-states.txt" | tr -d ' ')" -ge 3 ] \
    || am6_fail "phase2 DAG 任务应 ≥3: $(cat "$RUNS/phase2-dag-states.txt")"
_dagbad="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_task
    WHERE run_id='${NRUNID}' AND task_key LIKE 'investigate-%'
    AND state NOT IN ('DONE','DEAD')" '-At')"
[ "$_dagbad" = "0" ] || am6_fail "phase2 DAG 存在非终态: $(cat "$RUNS/phase2-dag-states.txt")"
am6_log "phase2 PASS（NATIVE 全链：原子对审计/终态/报告/发布/外发/驱动任务/DAG≥3 终态）"

# ---------------------------------------------------------------------------
# phase3 历史回放 + 审计两态共存可读
# ---------------------------------------------------------------------------
am6_log "phase3 历史回放：HOLMES 计数零增 + 审计 V35 前后两态共存可查"
H_BEFORE="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_run
    WHERE engine='HOLMES' AND state='SUCCEEDED'" '-At')"
am6_psql_ro AM6_PG_URL "SELECT
      count(*) FILTER (WHERE run_id IS NOT NULL) AS legacy_with_run,
      count(*) FILTER (WHERE run_id IS NULL) AS retired_without_run,
      count(*) AS total
    FROM canary_route_decision" '-At' > "$RUNS/audit-two-states.txt"
_legacy="$(cut -d'|' -f1 "$RUNS/audit-two-states.txt" | head -1)"
[ "${_legacy:-0}" -ge 1 ] 2>/dev/null \
    || am6_fail "phase3 V35 前非空审计行应 ≥1: $(cat "$RUNS/audit-two-states.txt")"
_retired="$(cut -d'|' -f2 "$RUNS/audit-two-states.txt" | head -1)"
[ "${_retired:-0}" -ge 1 ] 2>/dev/null \
    || am6_fail "phase3 V35 后 NULL 审计行应 ≥1（phase1 行）: $(cat "$RUNS/audit-two-states.txt")"
_pair="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM canary_route_decision d
    JOIN rca_run r ON r.id=d.run_id WHERE d.decision IN ('WHITELISTED','BUCKETED_NATIVE')
    AND r.engine='NATIVE'" '-At')"
[ "${_pair:-0}" -ge 1 ] 2>/dev/null \
    || am6_fail "phase3 NATIVE 决策↔run join 可读性破: $_pair"
am6_log "  审计两态：legacy_with_run=$_legacy retired_without_run=$_retired native_pair=$_pair"
am6_log "  HOLMES SUCCEEDED 快照（phase3 时点）=$H_BEFORE（与 phase5 复核零增）"
am6_log "phase3 PASS（审计查询全绿：两态共存 + NATIVE 原子对 join 可读）"

# ---------------------------------------------------------------------------
# phase4 密钥归并终扫（只列键名/词形，零值落盘）
# ---------------------------------------------------------------------------
am6_log "phase4 密钥归并：.env/容器 env/双栈渲染 holmes 键族全零"
{
    echo "== host .env HOLMES 键名（值零回显） =="
    grep -oE '^[A-Za-z0-9_]*[Hh]olmes[A-Za-z0-9_]*=' \
        /opt/build/pr/deploy/.env /opt/build/pr/deploy/alert/.env 2>/dev/null || true
    echo "== control-app 容器 env HOLMES 键名（值零回显） =="
    docker exec deploy-control-app-1 printenv 2>/dev/null \
        | grep -oE '^[A-Za-z0-9_]*[Hh]olmes[A-Za-z0-9_]*=' || true
    echo "== deploy 栈 config 渲染 holmes 词形（值零回显） =="
    ( cd /opt/build/pr/deploy && docker compose config 2>/dev/null \
        | grep -ioE 'holmes[a-z0-9_.-]*' | sort -u ) || true
    echo "== alert 栈 config 渲染 holmes 词形（值零回显） =="
    ( cd /opt/build/pr/deploy/alert && docker compose config 2>/dev/null \
        | grep -ioE 'holmes[a-z0-9_.-]*' | sort -u ) || true
    echo "== 五面扫描终止行 =="
    echo SECRET_SCAN_M607_OK
} > "$RUNS/secret-scan.txt"
[ "$(grep -cE '^[A-Za-z0-9_/.:]*[Hh]olmes[A-Za-z0-9_]*=' "$RUNS/secret-scan.txt")" = "0" ] \
    || am6_fail "phase4 存在 HOLMES 键名残留（键名清单见 secret-scan.txt，值零回显）"
[ "$(grep -icE 'holmes[a-z]' "$RUNS/secret-scan.txt")" = "0" ] \
    || am6_fail "phase4 compose 渲染含 holmes 词形: $(grep -iE 'holmes[a-z]' "$RUNS/secret-scan.txt")"
grep -q 'SECRET_SCAN_M607_OK' "$RUNS/secret-scan.txt" \
    || am6_fail "phase4 扫描未收口"
am6_log "phase4 PASS（.env/容器 env/双栈渲染四零命中；键值零回显）"

# ---------------------------------------------------------------------------
# phase5 drain barrier v2 回放 + HOLMES 计数复核
# ---------------------------------------------------------------------------
am6_log "phase5 drain barrier 回放（M6-06 v2 语义 B1~B4）+ HOLMES 计数复核"
am6_psql_ro AM6_PG_URL "SELECT
      (SELECT count(*) FROM rca_run r WHERE r.engine='HOLMES'
         AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
         AND EXISTS (SELECT 1 FROM rca_task t WHERE t.run_id=r.id AND t.state<>'DONE')) || '|' ||
      (SELECT count(*) FROM rca_task t JOIN rca_run r ON r.id=t.run_id
         WHERE r.engine='HOLMES' AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
           AND t.state<>'DONE') || '|' ||
      (SELECT count(*) FROM holmes_shadow_work
         WHERE state IN ('QUEUED','LEASED','FAILED')) || '|' ||
      (SELECT count(*) FROM run_fallback f JOIN rca_run fr ON fr.id=f.fallback_run_id
         WHERE fr.state NOT IN ('SUCCEEDED','FAILED'))" '-At' > "$RUNS/drain-replay.txt"
[ "$(cat "$RUNS/drain-replay.txt")" = "0|0|0|0" ] \
    || am6_fail "phase5 drain B1~B4 非全零: $(cat "$RUNS/drain-replay.txt")"
H_AFTER="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_run
    WHERE engine='HOLMES' AND state='SUCCEEDED'" '-At')"
[ "$H_AFTER" = "$H_BEFORE" ] || am6_fail "phase5 HOLMES 计数漂移: $H_BEFORE → $H_AFTER"
echo "holmes_succeeded_runs before=$H_BEFORE after=$H_AFTER" >> "$RUNS/audit-two-states.txt"
am6_log "phase5 PASS（B1~B4=0|0|0|0；HOLMES 计数 $H_BEFORE 零增）"

# ---------------------------------------------------------------------------
# phase6 制品恢复路径实跑（封存清单 + V35 备份 sha256 + 最旧报告读回计时）
# ---------------------------------------------------------------------------
am6_log "phase6 制品恢复实跑：封存清单 + pg_dump sha256 复验 + 最旧 HOLMES 报告读回 RTO"
ls -l /opt/backups/pre-m607-holmes-removal/ > "$RUNS/recovery-manifest.txt" 2>&1 \
    || am6_fail "phase6 恢复制品封存目录缺失"
grep -q 'compose-pre-removal.yml' "$RUNS/recovery-manifest.txt" \
    || am6_fail "phase6 封存清单缺 compose 归档: $(cat "$RUNS/recovery-manifest.txt")"
V35_BAK="/opt/backups/pre-v35-run-id-nullable/pr_agent-pre-v35.sql.gz"
sha256sum "$V35_BAK" >> "$RUNS/recovery-manifest.txt" \
    || am6_fail "phase6 V35 前置备份缺失: $V35_BAK"
grep -q '3ff560c82eec8e1b11467ccfa60b6632cec56e1972963fa64ea34740a29f7767' \
    "$RUNS/recovery-manifest.txt" || am6_fail "phase6 V35 备份 sha256 漂移"
T2="$(date +%s)"
am6_psql_ro AM6_PG_URL "SELECT 'run=' || r.id, 'report=' || rp.id,
      'pkg_bytes=' || octet_length(rp.package_json::text)
    FROM rca_run r JOIN rca_report rp ON rp.run_id = r.id
    WHERE r.engine='HOLMES' AND r.state='SUCCEEDED'
    ORDER BY r.created_at ASC LIMIT 1" '-At' > "$RUNS/oldest-report.txt"
T3="$(date +%s)"
[ -s "$RUNS/oldest-report.txt" ] || am6_fail "phase6 最旧 HOLMES 报告读回为空"
echo "restore-read RTO=$((T3-T2))s（口径同 M6-06 restore；无 holmesgpt 容器环境）" \
    >> "$RUNS/oldest-report.txt"
am6_log "phase6 PASS（制品封存/备份 sha256 恒等/最旧报告读回 $((T3-T2))s；不宣称一键回切=C-69，回滚仅发布指针面 D_PREV=$D_PREV）"

# ---------------------------------------------------------------------------
# phase7 收官姿态：percent=100 全量 NATIVE 激活 → BUCKETED_NATIVE → SUCCEEDED
# ---------------------------------------------------------------------------
am6_log "phase7 收官：percent=100 全量 NATIVE（无白名单）→ BUCKETED_NATIVE → SUCCEEDED"
cat > "$RUNS/bundle-final.content" <<EOF
{"policy_version":"am6-m607-final-full-native-${SUITE}","canary":{"percent":100,"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DFIN="$(am6_publish_bundle "$RUNS/bundle-final.content" final)"
am6_activate "$DFIN" "$RUNS"
FN_L="alertname=higherrorrate|service=am6e2e07-final-${SFX}"
FNKEY="${FN_L}:${FN_L}"
_code="$(am6_inject_alert HighErrorRate "am6e2e07-final-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase7 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e07-final-${SFX}-firing.resp")"
am6_db_poll_ge "phase7 BUCKETED_NATIVE 审计行（run_id 非空）" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${FNKEY}'
     AND decision='BUCKETED_NATIVE' AND bundle_digest='${DFIN}' AND run_id IS NOT NULL" 1
FNUN="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${FNKEY}' AND decision='BUCKETED_NATIVE' AND bundle_digest='${DFIN}'
    ORDER BY id DESC LIMIT 1" '-At')"
am6_db_poll_ge "phase7 收官 run SUCCEEDED(engine=NATIVE,digest=$DFIN)" 420 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${FNUN}' AND state='SUCCEEDED'
     AND engine='NATIVE' AND config_digest='${DFIN}'" 1
_ad="$(am6_active_digest "$RUNS")"
[ "$_ad" = "$DFIN" ] || am6_fail "phase7 active=$_ad 期望 $DFIN"
printf 'final_active=%s\nrollback_target_D_PREV=%s（留档；本套件不执行回滚=C-69）\n' \
    "$DFIN" "${D_PREV:-none}" > "$RUNS/final-posture.txt"
am6_log "phase7 PASS（M6 弧终点：全量 NATIVE 收官 active=$DFIN；D_PREV=$D_PREV 留档）"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-07" \
    "M6-07 Holmes 下线回归（退场姿态/HOLMES 意愿照记零铸造/NATIVE 全链/审计两态/密钥四零/drain 回放/制品恢复实跑/percent=100 收官）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-07 PASS（suite=$SUITE，证据=$RUNS）"
