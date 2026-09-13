#!/bin/sh
# rr1415-run.sh —— RR14/15 执行器（OR-04 凭据面；隔离栈 rriso；在栈零触碰）
# RR15-A compose :? 门 / RR15-B App requireRealKey 门 / RR14 键轮换（K1→K2 撤旧）
set -u
exec 9>/tmp/rr1415.lock
flock -n 9 || { echo "已有实例在跑（flock 拒绝并发）"; exit 1; }
EV=/opt/build/pr/rr-iso/rr-iso.env
ISO=/opt/build/pr/rr-iso
COMPOSE="docker compose -p rriso -f $ISO/docker-compose.iso.yml --env-file"
LOGD=/opt/build/pr-logs/rr1415
mkdir -p "$LOGD"
: > "$LOGD/rr15-verdicts.txt"
: > "$LOGD/rr14-verdicts.txt"
PGEXEC="docker exec rriso-postgres-1 psql"

echo "== 0) 外联网就绪（litellm/prometheus/loki 增量挂 rriso-net；不改在栈任何面）=="
docker network create rriso-net 2>/dev/null || true
for c in litellm-am3 prometheus-am0 $(docker ps --format '{{.Names}}' | grep -iE '^loki|^deploy-loki' | head -1); do
  docker network connect rriso-net "$c" 2>/dev/null && echo "connected $c" || echo "already/$c"
done

echo "== RR15-A：缺 AGENT_MODEL_API_KEY → compose 必须拒起 =="
sh /opt/build/rr-iso-gen-env.sh >/dev/null   # 不传 KEY → env 无该行
( cd "$ISO" && $COMPOSE "$EV" up -d control-app ) > "$LOGD/rr15a-compose.log" 2>&1
RC=$?
echo "compose rc=$RC（非 0=拒）"; grep -iE 'AGENT_MODEL_API_KEY|not set|required' "$LOGD/rr15a-compose.log" | head -3
docker ps -a --filter name=rriso --format '{{.Names}}' | head -3
[ "$RC" != "0" ] && [ -z "$(docker ps -a --filter name=rriso-control --format '{{.Names}}')" ] \
  && echo "RR15-A PASS（compose :? 拒起+零容器）" | tee -a "$LOGD/rr15-verdicts.txt" \
  || echo "RR15-A FAIL（见 rr15a-compose.log）" | tee -a "$LOGD/rr15-verdicts.txt"

echo "== RR15-B：AGENT_MODEL_API_KEY=placeholder → App 启动必须 fail-closed =="
sh /opt/build/rr-iso-gen-env.sh "placeholder" >/dev/null
( cd "$ISO" && $COMPOSE "$EV" up -d control-app ) > "$LOGD/rr15b-up.log" 2>&1
# 先等容器真起（fresh PG flyway 先行；上限 240s）
i=0; B_STARTED=no
while [ $i -lt 48 ]; do
  ST=$(docker inspect rriso-control-app-1 --format '{{.State.Status}}-{{.State.Restarting}}' 2>/dev/null || echo absent)
  echo "$ST" | grep -qE '^running|^exited' && { B_STARTED=yes; break; }
  sleep 5; i=$((i+1))
done
# 起后双门观察窗（上限 180s）：拒启动行出现=PASS 面 / health 200=弱默认放行 FAIL /
# 超时=证据留档 FAIL（1CPU 限流栈 Spring 到校验点可 >30s，固定 sleep 采样会竞态——实证）
i=0; B_VERDICT=pending
while [ $i -lt 36 ]; do
  docker logs rriso-control-app-1 > "$LOGD/rr15b-app.log" 2>&1
  B_HIT=$(grep -c '拒绝启动' "$LOGD/rr15b-app.log" 2>/dev/null || true)
  B_HEALTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:18091/actuator/health)
  [ "${B_HIT:-0}" -ge 1 ] && { B_VERDICT=reject; break; }
  [ "$B_HEALTH" = "200" ] && { B_VERDICT=weakpass; break; }
  sleep 5; i=$((i+1))
done
if [ "$B_VERDICT" = "reject" ]; then
  echo "RR15-B PASS（container=$ST started=$B_STARTED 观察拍=$i；health=$B_HEALTH；requireRealKey 拒启动面在；无弱默认放行）" | tee -a "$LOGD/rr15-verdicts.txt"
elif [ "$B_VERDICT" = "weakpass" ]; then
  echo "RR15-B FAIL（placeholder 被 health 200 放行=弱默认（BAD）——见 rr15b-app.log）" | tee -a "$LOGD/rr15-verdicts.txt"
else
  echo "RR15-B FAIL（观察窗 180s 内未见拒启动行也未见 health 200（container=$ST health=$B_HEALTH）——见 rr15b-app.log）" | tee -a "$LOGD/rr15-verdicts.txt"
fi
( cd "$ISO" && $COMPOSE "$EV" down ) > "$LOGD/rr15b-down.log" 2>&1

echo "== RR14：K1 铸造 → 上靶 → 审计快照 → 轮换 K2 撤 K1 → 双面断言 =="
echo "-- 清场：删除历史 rr14-* 测试键（上次尝试的孤儿键卫生）--"
docker exec litellm-am3 python3 -c "
import urllib.request,json,os
def call(path,payload,method='POST'):
    req=urllib.request.Request('http://127.0.0.1:4000'+path,data=json.dumps(payload).encode() if payload else None,
      headers={'Authorization':'Bearer '+os.environ['LITELLM_MASTER_KEY'],'Content-Type':'application/json'},method=method)
    return json.load(urllib.request.urlopen(req))
keys=call('/key/list',None,'GET').get('keys',[])
old=[]
for k in keys:
    if isinstance(k,dict) and str(k.get('key_alias','')).startswith('rr14-'):
        v=k.get('key') or k.get('token')
        if v: old.append(v)
print('历史 rr14 键清理:', (str(len(old))+' 把已删') if old else '无',
      call('/key/delete',{'keys':old}).get('error','ok') if old else '')
" 2>&1 | tail -2
STS=$(date +%s)
K1=$(docker exec litellm-am3 python3 -c "
import urllib.request,json,os
req=urllib.request.Request('http://127.0.0.1:4000/key/generate',
  data=json.dumps({'key_alias':'rr14-k1-$STS','models':['deepseek-v4-flash-0731']}).encode(),
  headers={'Authorization':'Bearer '+os.environ['LITELLM_MASTER_KEY'],'Content-Type':'application/json'})
print(json.load(urllib.request.urlopen(req))['key'])")
K1FINGER=$(printf '%s' "$K1" | sha256sum | cut -c1-12)
echo "K1 铸造 ok：alias=rr14-k1-$STS sha12=$K1FINGER（键值不落日志）" | tee "$LOGD/rr14-key-fingers.txt"
sh /opt/build/rr-iso-gen-env.sh "$K1" >/dev/null
( cd "$ISO" && $COMPOSE "$EV" up -d control-app ) > "$LOGD/rr14-up-k1.log" 2>&1
i=0; HC=000
while [ $i -lt 30 ]; do HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18091/actuator/health); [ "$HC" = 200 ] && break; sleep 5; i=$((i+1)); done
echo "K1 栈 health=$HC"; [ "$HC" = 200 ] || { echo "RR14 ABORT：K1 栈未起（rr14-up-k1.log）"; exit 2; }

echo "-- K1 车：注入告警 → run 收敛 → 模型调用审计行（K1 时代）--"
RID1=$(sh /opt/build/rr1415-inject.sh 2>&1 | tail -1)
echo "run1=$RID1"
$PGEXEC -U postgres -d pr_agent -At -c "select id||'|'||state from rca_model_call where run_id='$RID1' order by created_at" > "$LOGD/rr14-audit-k1.txt" || true
wc -l < "$LOGD/rr14-audit-k1.txt"
$PGEXEC -U postgres -d pr_agent -At -c "select run_id||' rows='||count(*)||' states='||string_agg(distinct state,',') from rca_model_call where run_id='$RID1' group by run_id" | tee "$LOGD/rr14-audit-k1-summary.txt"

echo "-- 轮换：铸 K2 → 撤 K1 --"
K2=$(docker exec litellm-am3 python3 -c "
import urllib.request,json,os
req=urllib.request.Request('http://127.0.0.1:4000/key/generate',
  data=json.dumps({'key_alias':'rr14-k2-$STS','models':['deepseek-v4-flash-0731']}).encode(),
  headers={'Authorization':'Bearer '+os.environ['LITELLM_MASTER_KEY'],'Content-Type':'application/json'})
print(json.load(urllib.request.urlopen(req))['key'])")
K2FINGER=$(printf '%s' "$K2" | sha256sum | cut -c1-12)
echo "K2 指纹 sha12=$K2FINGER" | tee -a "$LOGD/rr14-key-fingers.txt"
docker exec litellm-am3 python3 -c "
import urllib.request,json,os
req=urllib.request.Request('http://127.0.0.1:4000/key/delete',
  data=json.dumps({'keys':['$K1']}).encode(),
  headers={'Authorization':'Bearer '+os.environ['LITELLM_MASTER_KEY'],'Content-Type':'application/json'})
r=json.load(urllib.request.urlopen(req))
print('K1 delete:', 'ok' if not r.get('error') else 'ERROR（响应不含键值，不回显）')" | tee -a "$LOGD/rr14-key-fingers.txt"

echo "-- 旧键失效断言：K1 直连 chat completion 必须 401/403 --"
docker exec litellm-am3 python3 -c "
import urllib.request,json,sys
req=urllib.request.Request('http://127.0.0.1:4000/v1/chat/completions',
  data=json.dumps({'model':'deepseek-v4-flash-0731','messages':[{'role':'user','content':'ping'}],'max_tokens':4}).encode(),
  headers={'Authorization':'Bearer $K1','Content-Type':'application/json'})
try:
    urllib.request.urlopen(req); print('K1 STATUS=200 (BAD)')
except urllib.error.HTTPError as e:
    print('K1 STATUS=%d (期望 401/403)' % e.code)" | tee "$LOGD/rr14-k1-revoked.txt"

echo "-- 新键上靶：env K1→K2 重建 → health → run2 收敛 --"
sed -i "s|^AGENT_MODEL_API_KEY=.*|AGENT_MODEL_API_KEY=$K2|" "$EV"
( cd "$ISO" && $COMPOSE "$EV" up -d control-app ) > "$LOGD/rr14-up-k2.log" 2>&1
i=0; HC=000
while [ $i -lt 30 ]; do HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18091/actuator/health); [ "$HC" = 200 ] && break; sleep 5; i=$((i+1)); done
echo "K2 栈 health=$HC"
RID2=$(sh /opt/build/rr1415-inject.sh 2>&1 | tail -1)
echo "run2=$RID2"
$PGEXEC -U postgres -d pr_agent -At -c "select 'run2 rows='||count(*)||' succ='||count(*) filter (where state='SUCCESS')||' authden='||count(*) filter (where state='FAILED' and error_code='AUTH_DENIED') from rca_model_call where run_id='$RID2'" | tee "$LOGD/rr14-audit-k2.txt"

echo "-- 审计不变断言：K1 时代行逐字节复对 --"
$PGEXEC -U postgres -d pr_agent -At -c "select id||'|'||state from rca_model_call where run_id='$RID1' order by created_at" > "$LOGD/rr14-audit-k1-after.txt"
if diff -q "$LOGD/rr14-audit-k1.txt" "$LOGD/rr14-audit-k1-after.txt" >/dev/null 2>&1; then
  echo "审计不变 PASS" | tee -a "$LOGD/rr14-verdicts.txt"
else
  echo "审计不变 FAIL（对照 rr14-audit-k1*.txt）" | tee -a "$LOGD/rr14-verdicts.txt"
fi
K1REV=$(grep -o 'STATUS=40[13]' "$LOGD/rr14-k1-revoked.txt" | head -1)
K2SUCC=$(grep -o 'succ=[0-9]*' "$LOGD/rr14-audit-k2.txt" | head -1); K2S=${K2SUCC#succ=}
K2AUTH=$(grep -o 'authden=[0-9]*' "$LOGD/rr14-audit-k2.txt" | head -1); K2A=${K2AUTH#authden=}
[ -n "$K1REV" ] && echo "旧键失效 PASS（$K1REV）" | tee -a "$LOGD/rr14-verdicts.txt" || echo "旧键失效 FAIL" | tee -a "$LOGD/rr14-verdicts.txt"
# 新键有效=K2 时代 ≥1 真成功调用 且 零 AUTH_DENIED（OUTPUT_BUDGET_EXHAUSTED 等
# 模型面失败与凭据无关——deepseek 推理烧输出预算为已知行为，2026-09-13 实证）
if [ -n "$K2S" ] && [ "$K2S" -ge 1 ] && [ "${K2A:-1}" = "0" ]; then
  echo "新键有效 PASS（run2 $K2SUCC 真成功 $K2AUTH 凭据面零拒）" | tee -a "$LOGD/rr14-verdicts.txt"
else
  echo "新键有效 FAIL（$K2SUCC $K2AUTH——须 succ≥1 且 authden=0）" | tee -a "$LOGD/rr14-verdicts.txt"
fi

echo "-- 在栈不变量（RR14 全程零触碰）--"
curl -s -o /dev/null -w 'standing-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health | tee -a "$LOGD/rr14-verdicts.txt"
docker inspect deploy-control-app-1 --format 'standing-restarts={{.RestartCount}}' | tee -a "$LOGD/rr14-verdicts.txt"
echo "== 完成（隔离栈保留供 RR21 复用；rriso down 由后续统一执行）=="
