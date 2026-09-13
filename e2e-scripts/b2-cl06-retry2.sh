#!/bin/sh
# ============================================================================
# b2-cl06-retry2.sh —— B2 CL-06 v2 试验包裹器（S0/U01 验收 N06/N07）
#
# v1→v2 变更：
#   - Run ID 取自本案 driver 的 RUN_ID_RESULT= 输出行（零 ls -t——共享环境串案面 N07）
#   - 退出码纪律（N06）：有效试验任一 FAIL/ERROR → exit 1；否则任一 verify2=0
#     → exit 0；全 INCONCLUSIVE → exit 3。质量口径=固定 N 次有效试验全记录，
#     不以「过一次即算稳定」收场（工程重试允许，但全部次数入档）。
#   - 无效试验（场景未成立=phase4 零委派，qwen 采样面）如实记录、不计入 N、
#     不拖垮聚合，但受总尝试上限约束防死循环。
# ============================================================================
cd /opt/build/pr/deploy
OV=/opt/build/b2-cl06-override.yml   # v5 场景：两批委派序（N18 非空 witness 面）
LOGD=/opt/build/pr-logs
RUNSD=/opt/build/runs-b2cl06v2

docker compose -p deploy -f docker-compose.yml -f "$OV" up -d control-app 2>&1 | tail -2
i=0; HC=000
while [ $i -lt 18 ]; do
  HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)
  [ "$HC" = "200" ] && break
  sleep 5
  i=$((i+1))
done
[ "$HC" = "200" ] || { echo "startup fail (health=$HC)"; exit 2; }
docker exec deploy-control-app-1 env | grep -q '两批委派' \
  || { echo "FAIL: v5 两批委派 prompt 未生效"; exit 2; }
echo "override-ok（v5 两批委派场景在）"

N="${R7_TRIALS:-3}"
MAXATT="${R7_MAX_ATTEMPTS:-6}"
valid=0; att=0; pass_seen=0; fail_seen=0; inval=0
AGG="$LOGD/b2-cl06-v2-aggregate.log"
: > "$AGG"
while [ $valid -lt $N ] && [ $att -lt $MAXATT ]; do
  att=$((att+1))
  echo "==== attempt $att (valid $valid/$N) ===="
  cd /opt/build/b2tree
  LOG="$LOGD/b2-cl06-v2-t${att}.log"
  DRV=0
  sh -c '. /opt/build/r7-operator-env.sh && export R7_PRIMARY_ALLOWLIST=prometheus.query,logs.query,prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,prometheus.rules,logs.aggregate && R7_RUNS_DIR='"$RUNSD"' sh ./e2e-b2-cl06-v2.sh' \
    > "$LOG" 2>&1 || DRV=$?
  RID="$(grep -o 'RUN_ID_RESULT=[0-9a-f-]*' "$LOG" | tail -1 | cut -d= -f2)"
  [ -n "$RID" ] || RID=unknown
  if grep -q 'phase4 零委派' "$LOG"; then
    inval=$((inval+1))
    echo "attempt $att INVALID（场景未成立：零委派采样面；run=$RID）" | tee -a "$AGG"
    continue
  fi
  D="$(grep -l "run=$RID" "$RUNSD"/*/run-id.txt 2>/dev/null | head -1)"
  V2="$(cat "$D/phase10-verify2.exit" 2>/dev/null || echo '?')"
  echo "attempt $att valid: drv=$DRV run=$RID verify2=$V2 dir=$D" | tee -a "$AGG"
  valid=$((valid+1))
  if [ "$DRV" != "0" ] || [ "$V2" = "1" ] || [ "$V2" = "2" ] || [ "$V2" = "?" ]; then
    fail_seen=1
    echo "  -> FAIL（drv=$DRV verify2=$V2；末错行：$(grep -E 'FAIL' "$LOG" | tail -1 | cut -c1-140)）" | tee -a "$AGG"
  elif [ "$V2" = "0" ]; then
    pass_seen=1
    echo "  -> PASS（verify2=0 结构+非空 witness 双过）" | tee -a "$AGG"
  else
    echo "  -> INCONCLUSIVE（verify2=3 结构过/槽恒空）" | tee -a "$AGG"
  fi
done

echo "invalid_trials=$inval valid_trials=$valid pass_seen=$pass_seen fail_seen=$fail_seen" | tee -a "$AGG"
if [ $fail_seen = 1 ]; then
  echo "RETRY2-VERDICT: FAIL（存在有效试验失败——全记录见 $AGG）"
  exit 1
fi
if [ $pass_seen = 1 ]; then
  echo "RETRY2-VERDICT: PASS"
  exit 0
fi
echo "RETRY2-VERDICT: INCONCLUSIVE（全部有效试验槽恒空——非空 witness 未取得）"
exit 3
