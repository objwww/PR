#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== [E-1] ESCALATED 真执行 op 行 ==='
Q "select 'op='||left(operation_id::text,8)||'|status='||status||'|dry_run='||dry_run||'|resource='||resource_uid from rca_operation where status in ('ESCALATED','FAILED_CONFIRMED') order by prepared_at desc limit 3"
echo '=== [E-2] 真执行事件链（seq 52-57） ==='
Q "select 'seq='||seq||' '||event_type||' :: '||coalesce(payload::text,'-') from rca_event where run_id='4950d39b-2196-4028-9c7b-ee8f39e793b3' and seq between 52 and 57 order by seq"
echo '=== [E-3] A/B/C 消融的 GUARDIAN/审批证据行（seq 34/58-79） ==='
Q "select 'seq='||seq||' '||event_type from rca_event where run_id='4950d39b-2196-4028-9c7b-ee8f39e793b3' and seq in (34,58,63,64) order by seq"
echo '=== [E-4] shadow-summary ==='
BEARER=$(docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER')
curl -s http://127.0.0.1:8080/api/mutation/shadow-summary -H "Authorization: Bearer $BEARER"
echo
exit 0
