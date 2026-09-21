#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo "run终态24h: $(P "select count(*) from rca_run where finished_at is not null and created_at >= now() - interval '24 hours';")"
echo "task终态24h: $(P "select count(*) from rca_task where state in ('DONE','DEAD','CANCELLED') and ready_since is not null and updated_at >= now() - interval '24 hours';")"
echo "llm有延迟24h: $(P "select count(*) from rca_model_call where created_at >= now() - interval '24 hours' and latency_ms is not null;")"
echo "tool结算24h: $(P "select count(*) from rca_tool_invocation where started_at >= now() - interval '24 hours' and settled_at is not null;")"
echo "成本合计微元: $(P "select sum(cost_micros) from rca_model_call where created_at >= now() - interval '24 hours' and cost_micros is not null;")"
echo "无定价调用: $(P "select count(*) from rca_model_call where created_at >= now() - interval '24 hours' and cost_micros is null;")"
echo "guardian事件7d: $(P "select count(*) from rca_event where event_type='GUARDIAN_REVIEWED' and created_at >= now() - interval '7 days';")"
echo "审批拒绝7d: $(P "select count(*) from approval_decisions d where d.decision='REJECTED' and d.decided_at >= now() - interval '7 days';")"
echo "隔离死信7d: $(P "select count(*) from alert_inbox where state in ('QUARANTINED','DEAD_LETTER') and received_at >= now() - interval '7 days';")"
echo "runP50: $(P "select round(percentile_cont(0.5) within group (order by extract(epoch from (finished_at - created_at)) * 1000)) from rca_run where finished_at is not null and created_at >= now() - interval '24 hours';")"
echo "taskP50: $(P "select round(percentile_cont(0.5) within group (order by extract(epoch from (updated_at - ready_since)) * 1000)) from rca_task where state in ('DONE','DEAD','CANCELLED') and ready_since is not null and updated_at >= now() - interval '24 hours';")"
