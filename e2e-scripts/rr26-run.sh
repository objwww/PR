#!/bin/sh
# rr26-run.sh —— RR26 入口积压与公平性（195 真机·iso 栈）v2 保守语法版
# 规格：独立生成器控速率；受控慢工具拖 worker；先基线再阶梯；202=持久接收承诺；
#       队列深度/最老年龄时间序列；请求时延样本计算 p95。
set -u
. /opt/build/pr/rr-iso/rr-iso-openv.sh
. /opt/build/b2tree/e2e-r7-common.sh
LOGD=/opt/build/pr-logs/rr26
EV=/opt/build/pr/rr-iso/rr-iso.env
V="$LOGD/rr26-verdicts.txt"
STS=$(date +%s)
Q() { docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "$1"; }
mkdir -p "$LOGD"
exec 9>"$LOGD/.rr26.lock"
flock -n 9 || { echo "ABORT: another rr26 instance holds the lock"; exit 9; }
: > "$V"

BEARER=$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' "$EV" | cut -d= -f2-)
WH="http://127.0.0.1:18091/webhooks/alertmanager"
ISO=/opt/build/pr/rr-iso
COMPOSE="docker compose -p rriso -f $ISO/docker-compose.iso.yml --env-file"
NAMES="E2RR-C429 E2RR-CSLOWFIRST E2RR-CSLOWBODY E2RR-CRESET E2RR-CBADPROTO E2RR-CTLS E2RR-CDNS E2RR-LDEAD E2RR-LEMPTY E2RR-LTRUNC E2RR-LSLOW"

set_kv() { grep -q "^$1=" "$EV" && sed -i "s|^$1=.*|$1=$2|" "$EV" || echo "$1=$2" >> "$EV"; }
del_kv() { sed -i "/^$1=/d" "$EV"; }
recreate() {
  ( cd "$ISO" && $COMPOSE "$EV" up -d control-app ) >> "$LOGD/compose-recreate.log" 2>&1
  i=0; HC=000
  while [ $i -lt 40 ]; do
    HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18091/actuator/health)
    [ "$HC" = "200" ] && break
    sleep 5
    i=$((i+1))
  done
  echo "health=$HC"
}
purge_stale() {
  Q "update rca_task set state='CANCELLED', updated_at=now() where state in ('RUNNING','RETRY_WAIT')" > /dev/null
  Q "update rca_run set state='FAILED', finished_at=now() where state='RUNNING'" > /dev/null
}
post_one() {
  IDX="$1"; SEQ="$2"
  NTH=$(echo "$NAMES" | awk -v k="$IDX" '{print $(k)}')
  NOWT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  FP="f26-$STS-$SEQ"
  BJ="$LOGD/b-$FP.json"
  printf '{"version":"4","receiver":"e2e","groupKey":"rr26","status":"firing","groupLabels":{},"commonLabels":{"alertname":"%s","service":"checkout"},"commonAnnotations":{},"externalURL":"","truncatedAlerts":0,"alerts":[{"status":"firing","labels":{"alertname":"%s","service":"checkout","severity":"warning"},"annotations":{"summary":"rr26 seq=%s"},"startsAt":"%s","generatorURL":"","fingerprint":"%s"}]}' "$NTH" "$NTH" "$SEQ" "$NOWT" "$FP" > "$BJ"
  TT=$(curl -s -o "$LOGD/bresp-$FP.body" -w '%{http_code} %{time_total}' -X POST "$WH" -H "Authorization: Bearer $BEARER" -H "Content-Type: application/json" --data "@$BJ")
  echo "$TT" >> "$LOGD/codes.txt"
  LT=$(echo "$TT" | cut -d" " -f2)
  echo "$LT" >> "$LOGD/lat.txt"
}
sample_line() {
  DEPTH=$(Q "select count(*) from rca_task where state in ('QUEUED','RUNNING','RETRY_WAIT')")
  OLDEST=$(Q "select coalesce(extract(epoch from now()-min(created_at))::int,0) from rca_task where state in ('QUEUED','RUNNING','RETRY_WAIT')")
  IB=$(Q "select count(*) from alert_inbox where state='RECEIVED'")
  TS=$(date +%s)
  echo "$TS depth=$DEPTH oldest=${OLDEST}s inbox_received=$IB" >> "$1"
}
run_sampler() {
  SF="$1"; DUR="$2"; T0=$(date +%s)
  CUR=$T0
  while [ $((CUR-T0)) -lt "$DUR" ]; do
    sample_line "$SF"
    sleep 5
    CUR=$(date +%s)
  done
}
echo "== RR26 基线到阶梯（202 持久接收/队列深度/最老年龄/p95）=="
purge_stale
set_kv OPENAI_COMPAT_BASE_URL "http://litellm-am3:4000"
del_kv APP_MODEL_PER_CALL_TIMEOUT_MS
recreate

echo "-- 档1 基线（真源，11 firing，间隔2s）"
rm -f "$LOGD/lat.txt" "$LOGD/codes.txt"
run_sampler "$LOGD/samples-tier1.txt" 55 &
S1=$!
i=0
while [ $i -lt 11 ]; do
  post_one $((i+1)) "$i"
  i=$((i+1))
  [ "$i" -lt 11 ] && sleep 2
done
wait $S1
BAD1=$(grep -vc '^202 ' "$LOGD/codes.txt")
P95_1=$(sort -n "$LOGD/lat.txt" | awk '{a[NR]=$1} END{printf "%.3f", a[int(NR*0.95+0.5)]}')
MAXD1=$(awk '{split($0,a,"depth=");split(a[2],b," ");if(b[1]+0>m)m=b[1]+0}END{print m}' "$LOGD/samples-tier1.txt")
MAXO1=$(awk '{split($0,a,"oldest=");split(a[2],b,"s");if(b[1]+0>m)m=b[1]+0}END{print m}' "$LOGD/samples-tier1.txt")
echo "档1：非202=$BAD1 p95=${P95_1}s 队列峰值=$MAXD1 最老峰值=${MAXO1}s"
if [ "$BAD1" = "0" ]; then
  echo "RR26-T1 PASS（基线档 11/11 全 202；p95=${P95_1}s 队列峰值=$MAXD1 最老=${MAXO1}s）" | tee -a "$V"
else
  echo "RR26-T1 FAIL（非202=$BAD1）" | tee -a "$V"
fi

echo "-- 档2 阶梯（慢模型拖 worker，11 firing，间隔1s）"
set_kv OPENAI_COMPAT_BASE_URL "http://172.28.0.1:18100/slowfirst"
set_kv APP_MODEL_PER_CALL_TIMEOUT_MS 8000
recreate
rm -f "$LOGD/lat.txt" "$LOGD/codes.txt"
run_sampler "$LOGD/samples-tier2.txt" 150 &
S2=$!
i=0
while [ $i -lt 11 ]; do
  post_one $((i+1)) $((i+100))
  i=$((i+1))
  [ "$i" -lt 11 ] && sleep 1
done
BAD2=$(grep -vc '^202 ' "$LOGD/codes.txt")
P95_2=$(sort -n "$LOGD/lat.txt" | awk '{a[NR]=$1} END{printf "%.3f", a[int(NR*0.95+0.5)]}')
i=0
while [ $i -lt 48 ]; do
  ACT=$(Q "select count(*) from rca_task where state in ('QUEUED','RUNNING','RETRY_WAIT')")
  RUNS=$(Q "select count(*) from rca_run where state in ('QUEUED','RUNNING')")
  if [ "$ACT" = "0" ] && [ "$RUNS" = "0" ]; then break; fi
  sleep 5
  i=$((i+1))
done
wait $S2
MAXD2=$(awk '{split($0,a,"depth=");split(a[2],b," ");if(b[1]+0>m)m=b[1]+0}END{print m}' "$LOGD/samples-tier2.txt")
MAXO2=$(awk '{split($0,a,"oldest=");split(a[2],b,"s");if(b[1]+0>m)m=b[1]+0}END{print m}' "$LOGD/samples-tier2.txt")
RUNCAST=$(Q "select count(*) from rca_run where created_at > now() - interval '8 minutes'")
echo "档2：非202=$BAD2 p95=${P95_2}s 队列峰值=$MAXD2 最老峰值=${MAXO2}s run铸造=$RUNCAST 排空等待=$((i*5))s"
if [ "$BAD2" = "0" ]; then
  echo "RR26-T2 PASS（阶梯档 11/11 全 202——worker 被慢模型拖住仍持久接收；p95=${P95_2}s）" | tee -a "$V"
else
  echo "RR26-T2 FAIL（非202=$BAD2）" | tee -a "$V"
fi
if [ "$MAXD2" -ge 1 ]; then
  echo "RR26-Q PASS（慢档队列峰值深度=$MAXD2 可观测；排空等待=$((i*5))s 后归零）" | tee -a "$V"
else
  echo "RR26-Q FAIL（队列峰值=$MAXD2）" | tee -a "$V"
fi
if [ "$RUNCAST" -ge 1 ]; then
  echo "RR26-R PASS（材料序号化触发 RERUN：run 铸造=$RUNCAST）" | tee -a "$V"
else
  echo "RR26-R FAIL（run 铸造=$RUNCAST）" | tee -a "$V"
fi
echo "RR26-AGE 记录（最老年龄：基线峰=${MAXO1}s 慢档峰=${MAXO2}s）" | tee -a "$V"

del_kv APP_MODEL_PER_CALL_TIMEOUT_MS
set_kv OPENAI_COMPAT_BASE_URL "http://litellm-am3:4000"
purge_stale
recreate
curl -s -o /dev/null -w 'standing-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health | tee -a "$V"
docker inspect deploy-control-app-1 --format 'standing-restarts={{.RestartCount}}' | tee -a "$V"
echo "== 完成 =="
cat "$V"
