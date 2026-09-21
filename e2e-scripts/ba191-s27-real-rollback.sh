#!/bin/bash
# BA-191 验收：S27 演练 → 审批 → service.rollback 真执行（非 dry_run）→ flagd 真翻回 → ROLLBACK 行 → drill PASS
log(){ echo "$(date +%H:%M:%S) $*"; }
Q(){ docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "$1"; }
TOK(){ docker exec deploy-control-app-1 printenv APP_OPERATOR_API_BEARER; }
CSRF_NEW(){ curl -s -c /tmp/jar191 http://127.0.0.1:8080/api/auth/csrf -o /dev/null; grep XSRF /tmp/jar191 | awk '{print $7}'; }
POST(){ local path=$1 body=$2; local X=$(CSRF_NEW); curl -s -b /tmp/jar191 -H "X-XSRF-TOKEN: $X" -H "Authorization: Bearer $(TOK)" -H "Content-Type: application/json" -X POST "http://127.0.0.1:8080$path" ${body:+-d "$body"}; }

log "P1 check firing alerts"
N=$(curl -s "http://127.0.0.1:9090/api/v1/alerts" | python3 -c "
import json,sys
al=[a for a in json.load(sys.stdin)['data']['alerts'] if a['state']=='firing']
print(len(al))")
log "firing=$N"
[ "$N" != "0" ] && { log "ABORT: 仍有 firing 告警，SYMPTOM_CLEAN 门会拒"; exit 1; }

log "P2 fire S27 drill round3"
R=$(POST /api/drills "{\"scenarioId\":\"S27\",\"targetEnv\":\"arena-195\",\"idempotencyKey\":\"ba191-s27-r5-$(date +%s)\"}")
log "drill: $R"
D=$(echo "$R" | python3 -c "import json,sys; print(json.load(sys.stdin).get('drillId',''))" 2>/dev/null)
[ -z "$D" ] && { log "ABORT: 未拿到 drillId"; exit 1; }

log "P3 watch approvals (D=$D)"
RID=""
for i in $(seq 1 150); do
  ROW=$(Q "select request_id, required_approvers from approval_request where state='PENDING' order by requested_at desc limit 1")
  if [ -n "$ROW" ]; then
    RID=$(echo "$ROW" | cut -d'|' -f1); NEED=$(echo "$ROW" | cut -d'|' -f2)
    log "PENDING request=$RID need=$NEED"
    R1=$(POST /api/mutation/decide "{\"requestId\":\"$RID\",\"approverId\":\"operator\",\"approverRole\":\"OPERATOR\",\"approved\":true}")
    log "decide1: $(echo "$R1" | head -c 200)"
    if [ "$NEED" = "2" ]; then
      R2=$(POST /api/mutation/decide "{\"requestId\":\"$RID\",\"approverId\":\"sre-li\",\"approverRole\":\"SRE\",\"approved\":true}")
      log "decide2: $(echo "$R2" | head -c 200)"
    fi
    break
  fi
  sleep 20
done
[ -z "$RID" ] && { log "ABORT: 50 分钟无 PENDING 审批"; exit 1; }

log "P4 watch real execution (outbox + change_event ROLLBACK + flagd)"
for i in $(seq 1 60); do
  OB=$(Q "select state||'|'||coalesce(dry_run::text,'-') from operation_outbox order by created_at desc limit 1")
  RB=$(Q "select count(*) from change_event where action='ROLLBACK'")
  FL=$(Q "select state from flagd_restore_ledger order by updated_at desc limit 1" 2>/dev/null)
  log "outbox=$OB rollback_rows=$RB flagd_ledger=$FL"
  case "$OB" in COMPLETED*|FAILED*|*FAILED*) break;; esac
  sleep 15
done

log "P5 drill final"
for i in $(seq 1 60); do
  S=$(Q "select state||'/'||coalesce(outcome,'-') from drill_job where id='$D'")
  log "drill=$S"
  case "$S" in CLOSED*|FAILED*|RECOVERY_FAILED*) break;; esac
  sleep 20
done

log "P6 evidence snapshot"
Q "select state, outcome, left(coalesce(terminal_reason,''),140) from drill_job where id='$D'"
Q "select id, state, dry_run, left(coalesce(last_error,''),80) from operation_outbox order by created_at desc limit 2"
Q "select action, actor, left(coalesce(rollback_of,''),20), created_at from change_event order by created_at desc limit 3"
Q "select flag_key, state, restored_value, updated_at from flagd_restore_ledger order by updated_at desc limit 2"
log "DONE"
