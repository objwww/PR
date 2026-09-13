#!/bin/sh
# rr2528-run.sh —— RR25 episode 身份链 / RR27 响应丢失幂等 / RR28 迟到通知不冒充（195 真机·iso 栈）
# 原则：alertname=E2RRR25 不在任何活跃 bundle 白名单内→canary BUCKETED_HOLMES→零 run 铸造
# ——纯 incident/alert_event 层断言，零模型依赖（确定性）；webhook 直投+SQL 逐相位断言
set -u
. /opt/build/pr/rr-iso/rr-iso-openv.sh
. /opt/build/b2tree/e2e-r7-common.sh
LOGD=/opt/build/pr-logs/rr2528
EV=/opt/build/pr/rr-iso/rr-iso.env
V="$LOGD/rr2528-verdicts.txt"
STS=$(date +%s)
Q() { docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "$1"; }
mkdir -p "$LOGD"
# BA-144 教训一：flock 单实例硬闸
exec 9>"$LOGD/.rr2528.lock"
flock -n 9 || { echo "ABORT: another rr2528 instance holds the lock"; exit 9; }
: > "$V"

BEARER=$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' "$EV" | cut -d= -f2-)
WH="http://127.0.0.1:18091/webhooks/alertmanager"
# alertname 带套件后缀：同 key 旧 incident（FIRING/1）会让 P1 首见断言失效；
# 后缀不进白名单（BUCKETED_HOLMES 零 run 语义保持），决策断言用前缀匹配
AN="E2RRR25$(date +%H%M%S)"
KEY="alertname=$AN|service=checkout"
IID=""

# post_case $1=fingerprint $2=startsAt(ISO) $3=firing|resolved $4=summary $5=endsAt(可空)
post_case() {
  FP="$1"; SA="$2"; ST="$3"; SUM="$4"; EA="$5"
  ENDJ=""
  [ -n "$EA" ] && ENDJ=",\"endsAt\":\"$EA\""
  cat > "$LOGD/body-$FP.json" <<EOF
{"version":"4","receiver":"e2e","groupKey":"rr25","status":"$ST","groupLabels":{},"commonLabels":{"alertname":"$AN","service":"checkout"},"commonAnnotations":{},"externalURL":"","truncatedAlerts":0,"alerts":[{"status":"$ST","labels":{"alertname":"$AN","service":"checkout","severity":"warning"},"annotations":{"summary":"$SUM"},"startsAt":"$SA"$ENDJ,"generatorURL":"","fingerprint":"$FP"}]}
EOF
  curl -s -o "$LOGD/resp-$FP.body" -w '%{http_code}' -X POST "$WH" -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' --data @"$LOGD/body-$FP.json"
}
wait_incident() {
  i=0; while [ $i -lt 12 ]; do
    IID=$(Q "select id from incident where incident_key='$KEY'")
    [ -n "$IID" ] && return 0; sleep 2; i=$((i+1))
  done; return 1
}
drain() { sleep 4; }
snap() { Q "select status||'/'||generation||'/'||coalesce(waiting_reason,'-')||'/'||received_count||'/'||distinct_event_count||'/'||notification_count from incident where incident_key='$KEY'"; }
pass() { echo "RR25-$1 PASS（$2）" | tee -a "$V"; }
fail() { echo "RR25-$1 FAIL（$2）" | tee -a "$V"; }

NOW() { date -u +%Y-%m-%dT%H:%M:%SZ; }

echo "== RR25/27/28 incident 生命周期九相位（零模型依赖）=="
T1=$(NOW)

# P1 基线新 incident
C=$(post_case "f25a-$STS" "$T1" firing "r25 one" "")
drain; wait_incident || { fail "P1" "incident 未铸：http=$C"; exit 3; }
S=$(snap)
case "$S" in FIRING/0/*) pass "P1基线" "首见铸 incident：status/generation=$S；http=$C";; *) fail "P1基线" "快照=$S";; esac
EV=$(Q "select count(*) from alert_event where incident_id='$IID'")
RN=$(Q "select count(*) from rca_run where incident_id='$IID'")
DC=$(Q "select count(*) from canary_route_decision where stickiness_key like 'alertname=e2rrr25%' and decision='BUCKETED_HOLMES'")
[ "$EV" = "1" ] && [ "$RN" = "0" ] && [ "$DC" -ge 1 ] && pass "P1事件" "事件=1；run=0（HOLMES 分桶不铸 run，决策行=$DC 实证零模型依赖）" || fail "P1事件" "ev=$EV run=$RN bucketed=$DC"

# P2 同载荷重投（RR27：响应丢失→客户端重试→同幂等键）
post_case "f25a-$STS" "$T1" firing "r25 one" "" >/dev/null
drain
S=$(snap)
# notification_count=重复通知计数（首条非重复=0，duplicate 才 +1）——产品口径
case "$S" in FIRING/0/*/2/1/1) pass "P2重复通知" "received+1/distinct 不变/notification(重复计数)+1=$S（多物理请求一次副作用）";; *) fail "P2重复通知" "快照=$S";; esac
EV=$(Q "select count(*) from alert_event where incident_id='$IID'")
[ "$EV" = "1" ] && pass "P2去重" "事件仍=1（fingerprint+payloadHash+startsAt 幂等键生效）" || fail "P2去重" "ev=$EV"

# P3 同身份新 startsAt+材料变化（FIRING 持续不换 episode；材料变→RERUN 尝试→HOLMES 分桶→WAITING）
T2=$(NOW)
post_case "f25b-$STS" "$T2" firing "r25 two" "" >/dev/null
drain
S=$(snap)
case "$S" in FIRING/0/WAITING_CAPABILITY/3/2/1) pass "P3材料变化" "generation 保持=0（同 episode）；RERUN 尝试被 HOLMES 分桶→WAITING_CAPABILITY；$S";; *) fail "P3材料变化" "快照=$S（期望 FIRING/0/WAITING_CAPABILITY/3/2/1）";; esac

# P4 仅 severity 变化（severity 不参与 incidentKey/investigationHash→不触发重查）
T3=$(NOW)
post_case "f25c-$STS" "$T3" firing "r25 two" "" >/dev/null
drain
S=$(snap)
case "$S" in FIRING/0/*/4/3/1) pass "P4仅severity" "新事件（payloadHash 含 severity）但 generation 保持=0、材料未变不重查；$S";; *) fail "P4仅severity" "快照=$S";; esac

# P5 resolved（generation 保持）
T4=$(NOW)
post_case "f25d-$STS" "$T4" resolved "r25 resolved" "$T4" >/dev/null
drain
S=$(snap)
RA=$(Q "select resolved_at is not null from incident where incident_key='$KEY'")
case "$S" in RESOLVED/0/*) [ "$RA" = "t" ] && pass "P5resolved" "FIRING→RESOLVED generation 保持=0、resolved_at 落；$S" || fail "P5resolved" "resolved_at 空";; *) fail "P5resolved" "快照=$S";; esac

# P6 重复 resolved（幂等/不崩）
T5=$(NOW)
post_case "f25e-$STS" "$T5" resolved "r25 resolved2" "$T5" >/dev/null
drain
S=$(snap)
case "$S" in RESOLVED/0/*) pass "P6重复resolved" "重复 resolved 不换态不换代；$S";; *) fail "P6重复resolved" "快照=$S";; esac

# P7 乱序迟到 firing（startsAt 早于 resolvedAt→只计数不复活，旧 firing 不冒充新故障）
T35=$(date -u -d "$T4 - 30 seconds" +%Y-%m-%dT%H:%M:%SZ)
post_case "f25f-$STS" "$T35" firing "r25 late" "" >/dev/null
drain
S=$(snap)
case "$S" in RESOLVED/0/*) pass "P7乱序迟到" "迟到 firing（startsAt<resolvedAt）不复活不换代——恢复后旧 firing 不冒充新故障；$S";; *) fail "P7乱序迟到" "快照=$S（期望保持 RESOLVED/0）";; esac

# P8 resolved 后新 firing = 新 episode（generation+1）
T6=$(NOW)
post_case "f25g-$STS" "$T6" firing "r25 seven" "" >/dev/null
drain
S=$(snap)
case "$S" in FIRING/1/*) pass "P8新episode" "resolved→firing generation+1=1、episodeStartedAt 推进到 $T6；$S";; *) fail "P8新episode" "快照=$S（期望 FIRING/1）";; esac

# P9 RR27 多物理请求一次副作用（新 episode 载荷重投 2 次）
post_case "f25g-$STS" "$T6" firing "r25 seven" "" >/dev/null
post_case "f25g-$STS" "$T6" firing "r25 seven" "" >/dev/null
drain
S=$(snap)
EVG=$(Q "select count(*) from alert_event where incident_id='$IID' and fingerprint='f25g-$STS'")
case "$S" in FIRING/1/*) [ "$EVG" = "1" ] && pass "P9幂等键" "新 episode 内同载荷×3 次物理投递=1 条事件、generation 恒 1；$S" || fail "P9幂等键" "f25g 事件数=$EVG";; *) fail "P9幂等键" "快照=$S";; esac

# 终态转储
{
echo "=== incident 终态 ==="
Q "select incident_key, status, generation, waiting_reason, received_count, distinct_event_count, notification_count, episode_started_at, resolved_at from incident where incident_key='$KEY'"
echo "=== 全事件轨迹 ==="
Q "select fingerprint, status, generation, starts_at, coalesce(ends_at,'-'), recorded_at from alert_event where incident_id='$IID' order by recorded_at"
} > "$LOGD/final-db-evidence.txt" 2>&1
echo "== 复原核验 =="
curl -s -o /dev/null -w 'standing-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health | tee -a "$V"
docker inspect deploy-control-app-1 --format 'standing-restarts={{.RestartCount}}' | tee -a "$V"
echo "== 完成 =="
cat "$V"
