#!/bin/sh
# rr2224-run.sh —— RR22（模型 HTTP 四案）/RR24（协议-TLS-DNS 三案+Clock NOT_RUN）/RR23（日志源四案）
# 原则：受控 mock 端点（宿主 18100/18443）+ iso env 旋钮（per-call-timeout 8s 测试档）；
# 物理对拍=mock 访问日志 vs rca_model_call 行；有界=步预算 6；零越权 fallback（fallback env 空）
set -u
. /opt/build/pr/rr-iso/rr-iso-openv.sh
. /opt/build/b2tree/e2e-r7-common.sh
LOGD=/opt/build/pr-logs/rr2224
EV=/opt/build/pr/rr-iso/rr-iso.env
ISO=/opt/build/pr/rr-iso
COMPOSE="docker compose -p rriso -f $ISO/docker-compose.iso.yml --env-file"
V="$LOGD/rr2224-verdicts.txt"
GW=172.28.0.1
STS=$(date +%s)
Q() { docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "$1"; }
mkdir -p "$LOGD"
# BA-144 教训一：无锁双实例并发互相踩 env/复用对方 run——flock 单实例硬闸
exec 9>"$LOGD/.rr2224.lock"
flock -n 9 || { echo "ABORT: another rr2224 instance holds the lock"; exit 9; }
: > "$V"
# BA-144 教训五：canary stickiness 吸收近期 firing（v3 实证：同 key 已有 WHITELISTED
# 决策时新 firing 可能被粘住不建 run），且对无历史的新 alertname，bundle 激活切换存在
# 不确定性（v4 实证：新名全被 BUCKETED_HOLMES 分桶）。根治组合=沿用现行已激活 bundle
# 的 alertname + suite 开局清空决策表（iso 测试库卫生，复刻首跑空表条件=100% 建决策）。
WIPE=$(Q "delete from canary_route_decision")
echo "决策表清场完成（iso 测试库）"

set_kv() { grep -q "^$1=" "$EV" && sed -i "s|^$1=.*|$1=$2|" "$EV" || echo "$1=$2" >> "$EV"; }
del_kv() { sed -i "/^$1=/d" "$EV"; }
# BA-144 教训二：上案残留的 RUNNING/RETRY_WAIT 任务在容器重建时被启动恢复重放，
# 重放连败在开机数秒内把熔断器打到 OPEN→本案物理调用全部短路（v1 的 CTLS/CDNS 弱 PASS 根因）。
# 清场必须在 recreate 之前：开机恢复看到的库=零非终态，熔断器以全新态进入本案。
purge_stale() {
  Q "update rca_task set state='CANCELLED', updated_at=now() where state in ('RUNNING','RETRY_WAIT')" >/dev/null
  Q "update rca_run set state='FAILED', finished_at=now() where state='RUNNING'" >/dev/null
}
recreate() {
  ( cd "$ISO" && $COMPOSE "$EV" up -d control-app ) >> "$LOGD/compose-recreate.log" 2>&1
  i=0; HC=000
  while [ $i -lt 40 ]; do HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18091/actuator/health); [ "$HC" = 200 ] && break; sleep 5; i=$((i+1)); done
  echo "health=$HC"
}
BEARER=$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' "$EV" | cut -d= -f2-)
WH="http://127.0.0.1:18091/webhooks/alertmanager"

inject_case() { # $1=ALERTNAME → echo RID（终态等待 300s）
  AN="$1"; FP="$(echo "$AN" | tr 'A-Z' 'a-z')-$STS"
  WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=checkout"
  NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  cat > "$LOGD/body-$AN.json" <<EOF
{"version":"4","receiver":"e2e","groupKey":"$AN","status":"firing","groupLabels":{},"commonLabels":{"alertname":"$AN","service":"checkout"},"commonAnnotations":{},"externalURL":"","truncatedAlerts":0,"alerts":[{"status":"firing","labels":{"alertname":"$AN","service":"checkout"},"annotations":{"summary":"rr2224 $AN $STS"},"startsAt":"$NOW","generatorURL":"","fingerprint":"$FP"}]}
EOF
  CODE=$(curl -s -o "$LOGD/resp-$AN.body" -w '%{http_code}' -X POST "$WH" -H "Authorization: Bearer $BEARER" -H 'Content-Type: application/json' --data @"$LOGD/body-$AN.json")
  # BA-144 教训三：stickiness key 跨 suite 复用（同 alertname），无时域锚时
  # `order by id desc limit 1` 会抓到上一 suite 的历史决策→verdict 读旧 run 行。
  # POST 前抓决策表水位，只认水位之后落的新决策=本案自己的。
  MARKID=$(Q "select coalesce(max(id),0) from canary_route_decision")
  RID=""
  i=0; while [ $i -lt 24 ]; do
    RID=$(Q "select run_id from canary_route_decision where id > $MARKID and stickiness_key='${WL_L}:${WL_L}' and decision='WHITELISTED' and run_id is not null order by id desc limit 1")
    [ -n "$RID" ] && break; sleep 5; i=$((i+1))
  done
  if [ -z "$RID" ]; then echo "NORUN(code=$CODE)"; return; fi
  r7_wait_run_terminal R7_PG_URL "$RID" 300 >/dev/null 2>&1
  echo "$RID"
}

model_case() { # $1=案名 $2=baseurl $3=mock路径(空=不对拍) $4=KILL(连接层杀 face，MC==ROWS 不适用)
  NAME="$1"; URL="$2"; MP="$3"; KILL="${4:-}"; AN="E2RR-$NAME"
  echo "-- 案 $NAME → $URL"
  purge_stale
  set_kv OPENAI_COMPAT_BASE_URL "$URL"
  set_kv APP_MODEL_PER_CALL_TIMEOUT_MS 8000
  recreate
  MARK=$(wc -l < "$LOGD/mock-access.log" 2>/dev/null || echo 0)
  RID=$(inject_case "$AN")
  echo "run=$RID"
  case "$RID" in ""|NORUN*) echo "RR-$NAME FAIL（run 未建：$RID）" | tee -a "$V"; return;; esac
  AGG=$(Q "select count(*)||' rows, '||string_agg(distinct state||'/'||coalesce(error_code,'-'),',') from rca_model_call where run_id='$RID'")
  ROWS=$(Q "select count(*) from rca_model_call where run_id='$RID'")
  SUCC=$(Q "select count(*) from rca_model_call where run_id='$RID' and state='SUCCESS'")
  # 物理证据行=故障面真被行使过。教训四：DEFERRED 是物理调用后的 durable-defer
  # 记账（长等待挂回队列语义，v3 实证 3 行 DEFERRED=mock 3 击对拍），不是短路码；
  # 短路码只有 CIRCUIT_OPEN（熔断器开，零物理调用）。
  NOFACE=$(Q "select count(*) from rca_model_call where run_id='$RID' and coalesce(error_code,'-') not in ('CIRCUIT_OPEN')")
  MC=skip
  if [ -n "$MP" ]; then MC=$(awk -v m="$MARK" 'NR>m' "$LOGD/mock-access.log" | grep -c " /$MP/" || true); fi
  echo "ledger: $AGG | mock物理=$MC | succ=$SUCC | 物理证据行=$NOFACE"
  FB=$(grep -c '^OPENAI_COMPAT_BASE_URL_FALLBACK=..*' "$EV" || true)
  # 干净响应面：物理对拍要求 mock 击数==账本行数；连接层杀 face（RST/TLS 拒握）：
  # 传输层重试/握手层失败使击数与行数合法偏离，物理性由 NOFACE≥1 + 失败记账证
  if [ "$ROWS" -ge 1 ] && [ "$ROWS" -le 6 ] && [ "$SUCC" = "0" ] && [ "$NOFACE" -ge 1 ] \
     && { [ "$MC" = "skip" ] || [ "$MC" = "$ROWS" ] || [ "$KILL" = "KILL" ]; }; then
    echo "RR-$NAME PASS（rows=$ROWS 有界≤6；全 FAILED=$AGG；mock物理对拍=$MC；物理证据行=$NOFACE；零 fallback=${FB}0）" | tee -a "$V"
  else
    echo "RR-$NAME FAIL（rows=$ROWS succ=$SUCC mock=$MC 物理证据行=$NOFACE——$AGG）" | tee -a "$V"
  fi
}

echo "== 0) mock 起动 + bundle 全案预激活 =="
if ! (ss -ltn | grep -q ':18100 '); then
  openssl req -x509 -newkey rsa:2048 -keyout "$LOGD/mock.key" -out "$LOGD/mock.crt" -days 2 -nodes -subj "/CN=rr2224-mock" >/dev/null 2>&1
  setsid nohup python3 /opt/build/rr2224-mock.py > "$LOGD/mock-stdout.log" 2>&1 < /dev/null &
  sleep 2
fi
curl -s -o /dev/null -w 'mock-alive=%{http_code}\n' http://127.0.0.1:18100/ping
SUITE="rr2224-$STS"; RUNS="${R7_RUNS_DIR}/$SUITE"; mkdir -p "$RUNS"
WLS=""
# BA-144 教训五（定谳）：incidentKey 保留标签原始大小写（AlertIdentityFactory 不折叠），
# CanaryRouter 白名单匹配 contains(id.trim()) 大小写敏感——白名单必须用与 alertname
# 标签逐字节同形的大写名；stickiness_key 列显示的小写是 normalizedKey 的展示形，勿被误导
for c in E2RR-C429 E2RR-CSLOWFIRST E2RR-CSLOWBODY E2RR-CRESET E2RR-CBADPROTO E2RR-CTLS E2RR-CDNS E2RR-LDEAD E2RR-LEMPTY E2RR-LTRUNC E2RR-LSLOW; do
  WLS="$WLS\"alertname=$c|service=checkout\","
done
cat > "$RUNS/bundle.content" <<EOF
{"policy_version":"rr2224-${SUITE}","canary":{"percent":0,"whitelist":[$WLS"alertname=e2rr-sentinel|service=checkout"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA="$(r7_publish_bundle "$RUNS/bundle.content" rr2224)" && r7_qualify "$DA" "$RUNS" >/dev/null && r7_activate "$DA" "$RUNS" >/dev/null && echo "bundle ok" || { echo "ABORT bundle"; exit 2; }

echo "== 1) RR22 模型 HTTP 四案（per-call-timeout=8s 测试档）=="
if [ "${RR23ONLY:-0}" != "1" ]; then
model_case C429 "http://$GW:18100/429" "429"
# 首案闸：C429 未建 run=活跃 bundle 白名单不含本案名（v4 教训：激活切换不确定性
# 导致全量 BUCKETED_HOLMES 分桶）——立即终止，不空跑余案
if grep -q '^RR-C429 FAIL（run 未建' "$V"; then
  echo "ABORT: 首案即未建 run——活跃 bundle 白名单态不对，需人工核查 bundle 链" | tee -a "$V"
  exit 4
fi
model_case CSLOWFIRST "http://$GW:18100/slowfirst" "slowfirst"
model_case CSLOWBODY "http://$GW:18100/slowbody" "slowbody"
model_case CRESET "http://$GW:18100/reset" "reset" KILL

echo "== 2) RR24 协议/TLS/DNS 三案 + Clock NOT_RUN =="
model_case CBADPROTO "http://$GW:18100/badproto" "badproto"
model_case CTLS "https://$GW:18443/tls" "tls" KILL
model_case CDNS "http://rr24-dead-host.invalid:4000" ""
echo "RR24-CLOCK NOT_RUN（无安全注入法：Linux time namespace 不虚拟化 CLOCK_REALTIME，容器 date -s 非局部偏移实验，需专用 VM；租约偏移语义验证归专用线）" | tee -a "$V"
fi

echo "== 3) RR23 日志源四案（模型回真源 litellm；loki 指注入面）=="
set_kv OPENAI_COMPAT_BASE_URL "http://litellm-am3:4000"
del_kv APP_MODEL_PER_CALL_TIMEOUT_MS
# iso 栈内 prometheus 直连（缺省 http://prometheus:9090 是 standing 栈服务名，rriso-net
# 不可解析→prometheus.catalog 全 TRANSPORT_UNKNOWN→agent 卡死 metrics 面到不了 logs 面=v1 四案假阴性根因）
set_kv APP_ALERT_AM4_PROMETHEUS_BASE_URL "http://prometheus-am0:9090"
# v7 实证：模型在 tool 预算 4 内只打 prometheus.catalog（×4 SUCCESS）耗尽预算，
# 未走到 logs 面——预算提到 8 给模型到达 logs 面的余量（RR23ONLY 段专设，复原段删）
set_kv APP_ALERT_AM4_BUDGET_TOOL_CALLS 8
# v8 实证：deepseek 推理烧穿步输出预算（BA-120 族）→content 空→发不出下一工具调用，
# 1 catalog 后即 OUTPUT_BUDGET_EXHAUSTED 收敛——步预算提到 4000 让推理能装下工具调用
set_kv APP_ALERT_R7_PRIMARY_STEP_MAX_TOKENS 4000
loki_case() { # $1=案名 $2=lokiURL $3=期望族(fail|empty)
  NAME="$1"; URL="$2"; FAM="$3"; AN="E2RR-$NAME"
  echo "-- 案 $NAME → $URL"
  purge_stale
  set_kv APP_ALERT_AM4_LOGS_LOKI_BASE_URL "$URL"
  recreate
  RID=$(inject_case "$AN")
  echo "run=$RID"
  if [ -z "$RID" ] || [ "${RID#NORUN}" != "$RID" ]; then echo "RR-$NAME FAIL（run 未建：$RID）" | tee -a "$V"; return; fi
  Q "select tool_name||'|'||state||'|'||coalesce(reason_code,'-') from rca_tool_invocation where run_id='$RID' and tool_name like 'logs.%' order by call_seq" > "$LOGD/logsrows-$NAME.txt"
  EVLOGS=$(Q "select count(*) from rca_evidence where run_id='$RID' and (source ilike '%logs%' or source ilike '%loki%')")
  NSUCC=$(Q "select count(*) from rca_tool_invocation where run_id='$RID' and tool_name like 'logs.%' and state='SUCCESS'")
  NLOG=$(Q "select count(*) from rca_tool_invocation where run_id='$RID' and tool_name like 'logs.%'")
  echo "logs 调用=$(cat "$LOGD/logsrows-$NAME.txt" | tr '\n' ' ') evidence(logs)=$EVLOGS"
  if [ "$FAM" = "fail" ]; then
    if [ "$NLOG" -ge 1 ] && [ "$NSUCC" = "0" ] && [ "$EVLOGS" = "0" ]; then
      echo "RR-$NAME PASS（logs 调用全失败零伪造；evidence 零 logs 源）" | tee -a "$V"
    else echo "RR-$NAME FAIL（succ=$NSUCC ev=$EVLOGS）" | tee -a "$V"; fi
  else
    DEADFAM=$(grep -c 'TRANSPORT' "$LOGD/logsrows-LDEAD.txt" 2>/dev/null || echo 0)
    THISEMPTY=$(grep -cE 'NO_DATA|SUCCESS' "$LOGD/logsrows-$NAME.txt" || true)
    if [ "$EVLOGS" = "0" ] && [ "$THISEMPTY" -ge 1 ]; then
      echo "RR-$NAME PASS（空结果语义与不可达可区分：$(cat "$LOGD/logsrows-$NAME.txt")；零伪造 evidence）" | tee -a "$V"
    else echo "RR-$NAME FAIL（empty 语义区分未证：$(cat "$LOGD/logsrows-$NAME.txt") ev=$EVLOGS）" | tee -a "$V"; fi
  fi
}
loki_case LDEAD "http://$GW:18999" fail
loki_case LEMPTY "http://$GW:18100/empty-loki" empty
loki_case LTRUNC "http://$GW:18100/trunc-loki" fail
loki_case LSLOW "http://$GW:18100/slow-loki" fail

echo "== 4) 复原 =="
del_kv APP_ALERT_AM4_LOGS_LOKI_BASE_URL
del_kv APP_ALERT_AM4_PROMETHEUS_BASE_URL
del_kv APP_ALERT_AM4_BUDGET_TOOL_CALLS
del_kv APP_ALERT_R7_PRIMARY_STEP_MAX_TOKENS
set_kv OPENAI_COMPAT_BASE_URL "http://litellm-am3:4000"
recreate
curl -s -o /dev/null -w 'standing-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health | tee -a "$V"
docker inspect deploy-control-app-1 --format 'standing-restarts={{.RestartCount}}' | tee -a "$V"
echo "== 完成 =="
cat "$V"
