#!/bin/sh
# incident/报告侦听 v2（195 宿主侧）：
#   incident（key 最新行）→ 最新 rca_run（独立查询，run 完成后 incident 的
#   current_rca_run_id 可能被清空，不能依赖它）→ STRUCTURE_VALIDATED 报告。
# 用法: watch_incident.sh <ALERTNAME> <TIMEOUT_SEC> <OUTFILE>
# OUTFILE 行: <incident_id> <incident_generation> <run_id> <report_id>
set -u
ALERTNAME=$1; TIMEOUT=$2; OUT=$3
KEY="alertname=${ALERTNAME}|service=order-arena|job=order-arena"
PSQL="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc"
DEADLINE=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  ROW=$($PSQL "SELECT id||','||generation FROM incident WHERE incident_key='$KEY' ORDER BY created_at DESC LIMIT 1" 2>/dev/null)
  INC=$(printf '%s' "$ROW" | cut -d, -f1)
  GEN=$(printf '%s' "$ROW" | cut -d, -f2)
  if [ -n "${INC:-}" ]; then
    RROW=$($PSQL "SELECT id||','||state FROM rca_run WHERE incident_id='$INC' ORDER BY created_at DESC LIMIT 1" 2>/dev/null)
    RUN=$(printf '%s' "$RROW" | cut -d, -f1)
    STATE=$(printf '%s' "$RROW" | cut -d, -f2)
    if [ -n "${RUN:-}" ]; then
      case "$STATE" in
        FAILED|CANCELLED)
          echo "WATCH_FAILED state=$STATE run=$RUN incident=$INC"; exit 2 ;;
      esac
      REP=$($PSQL "SELECT id FROM rca_report WHERE run_id='$RUN' AND validation_status='STRUCTURE_VALIDATED' LIMIT 1" 2>/dev/null)
      if [ "$STATE" = "SUCCEEDED" ] && [ -n "${REP:-}" ]; then
        printf '%s %s %s %s\n' "$INC" "$GEN" "$RUN" "$REP" > "$OUT"
        echo "WATCH_OK incident=$INC gen=$GEN run=$RUN report=$REP"
        exit 0
      fi
      echo "watch: incident=$INC gen=$GEN run=$RUN state=$STATE report=${REP:-pending}"
    else
      echo "watch: incident=$INC gen=$GEN run 待创建"
    fi
  else
    echo "watch: incident 未铸出 key=$KEY"
  fi
  sleep 10
done
echo "WATCH_TIMEOUT key=$KEY"
exit 1
