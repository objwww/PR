#!/bin/sh
. /opt/build/r7-operator-env.sh
echo '=== B4 运行脸：canary_evidence_sample（今晨 8 跑应已采集） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*), count(distinct evidence_class) from canary_evidence_sample;"
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select run_id, stickiness_key, evidence_class, observed_json->>'outcome' from canary_evidence_sample order by created_at desc limit 8;"
echo '=== canary_window_verdict（worker 拍评窗） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select window_seq, verdict, eligible_incidents, raw_counts->>'excluded_reason' from canary_window_verdict order by id desc limit 5;"
echo '=== O01 通知面：报告完成通知（最近） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from report_publication;"
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from incident_budget_entry;" 2>/dev/null | head -1
echo '=== O01 状态面：E2EA0CheckoutProbe incident 状态 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select incident_key, status, generation, received_count, resolved_at is not null as has_resolved from incident where incident_key like '%E2EA0%' order by last_event_at desc limit 3;"
exit 0
