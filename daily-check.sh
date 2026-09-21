#!/bin/sh
# 每日质量检查点数据拉取：北极星指标 + LLM 在环现状 + 风险面（195 现拉）
set +e
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA"
echo "@@A 北极星·eval_run 总量与近 24h 新增"
$PG -c "select 'total=' || count(*) from eval_run"
$PG -c "select 'last24h_new=' || count(*) from eval_run where created_at > now() - interval '24 hours'"
echo "@@B 北极星·最新 eval_run 明细"
$PG -c "select left(id::text,8) || ' | ' || state || ' | ' || model || ' | ds=' || dataset_version || ' | cov=' || coalesce(coverage::text,'-') || ' | cond_acc=' || coalesce(conditional_accuracy::text,'-') || ' | e2e=' || coalesce(end_to_end_hit_rate::text,'-') || ' | unres=' || coalesce(unresolved_rate::text,'-') || ' | fin=' || coalesce(finished_at::text,'-') from eval_run order by created_at desc limit 1"
echo "@@C LLM 在环·rca_model_call 近 24h 按状态"
$PG -c "select state || ' | ' || count(*) from rca_model_call where created_at > now() - interval '24 hours' group by state"
echo "@@D LLM 在环·近 24h 调用明细（run/角色/状态/错误码/token/耗时）"
$PG -c "select left(run_id::text,8) || ' | ' || role_id || ' | a' || action_seq || 'p' || physical_seq || ' | ' || state || ' | ' || coalesce(error_code,'-') || ' | tok=' || coalesce(usage->>'total_tokens','-') || ' | ' || coalesce(latency_ms::text,'-') || 'ms | ' || requested_model || ' | ' || created_at from rca_model_call where created_at > now() - interval '24 hours' order by created_at"
echo "@@E RCA run·feb48a0e 之后有无新增"
$PG -c "select left(id::text,8) || ' | ' || state || ' | ' || trigger_kind || ' | ' || created_at from rca_run where created_at > now() - interval '24 hours' order by created_at"
echo "@@F checkout 事故现态（含兜底：最近 5 条 incident）"
$PG -c "select left(id::text,8) || ' | ' || incident_key || ' | ' || status || ' | gen=' || generation || ' | run=' || coalesce(left(current_rca_run_id::text,8),'-') || ' | recv=' || received_count || ' | last=' || last_event_at from incident where incident_key ilike '%checkout%' order by updated_at desc limit 3"
$PG -c "select left(id::text,8) || ' | ' || incident_key || ' | ' || status || ' | ' || updated_at from incident order by updated_at desc limit 5"
echo "@@G 报告发布面·近 24h"
$PG -c "select left(id::text,8) || ' | report=' || left(report_id::text,8) || ' | ' || state || ' | ' || created_at from report_publication where created_at > now() - interval '24 hours' order by created_at" 2>/dev/null || $PG -c "select count(*) from report_publication"
echo "@@H demo 指标管道·checkout 序列存在性"
curl -s 'http://127.0.0.1:9090/api/v1/series' --data-urlencode 'match[]={service="checkout"}' --data-urlencode "start=$(date -u -d '-10 minutes' +%s)" | head -c 200; echo
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=count(up)' | head -c 200; echo
echo "@@I 基础设施·burn-generator 与关键容器"
docker ps --format '{{.Names}} | {{.Status}}' | grep -E 'burn-generator|control-app|alertmanager|prometheus|litellm|postgres' | head -10
echo "@@J AM 当前 firing"
curl -s http://127.0.0.1:9093/api/v2/alerts | head -c 600; echo
