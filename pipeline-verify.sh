#!/bin/sh
# 195 告警->事件->RCA 链路核查（网络恢复后跑）
set +e
echo '== 1. burn-generator 状态 =='
docker ps --filter name=burn-generator --format '{{.Names}} | {{.Status}}'
echo '== 2. AM 当前告警（标签+状态+接收器在 status 里看不到，先看 labels） =='
curl -s http://127.0.0.1:9093/api/v2/alerts | head -c 2500
echo
echo '== 3. SLI 实时值 =='
for w in 5m 30m 1h 2h 6h; do
  v=$(curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode "query=slo:sli_error:ratio_rate${w}{sloth_id=\"checkout-availability\"}" | tr ',' '\n' | tr -d '"')
  echo "$w: $(echo "$v" | grep -E 'value|metric' | tr '\n' ' ' | head -c 220)"
done
echo '== 4. incident 最近 5 行 =='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8) || ' | ' || incident_key || ' | ' || status || ' | gen=' || generation || ' | run=' || coalesce(left(current_rca_run_id::text,8),'-') || ' | recv=' || received_count || ' | notif=' || notification_count || ' | ' || updated_at from incident order by updated_at desc limit 5"
echo '== 5. alert_event / webhook_inbox 近 3h =='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from alert_event where created_at > now() - interval '3 hours'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8) || ' | ' || coalesce(incident_id::text,'-') || ' | ' || created_at from alert_event where created_at > now() - interval '3 hours' order by created_at desc limit 5" 2>/dev/null || echo '(alert_event 列差异)'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from webhook_inbox where created_at > now() - interval '3 hours'" 2>/dev/null || echo '(webhook_inbox 查询失败)'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select status || ' | ' || created_at from webhook_inbox where created_at > now() - interval '3 hours' order by created_at desc limit 5" 2>/dev/null || true
echo '== 6. rca_run 最近 5 行（含 last_error） =='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8) || ' | ' || state || ' | ' || trigger_kind || ' | started=' || coalesce(started_at::text,'-') || ' | fin=' || coalesce(finished_at::text,'-') || ' | err=' || coalesce(last_error::text,'-') || ' | ' || created_at from rca_run order by created_at desc limit 5"
echo '== 7. 最新 run 的模型调用/任务/报告 =='
NEWEST=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id from rca_run order by created_at desc limit 1")
echo "newest run: $NEWEST"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_model_call where run_id='$NEWEST'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_task where run_id='$NEWEST'" 2>/dev/null || echo '(rca_task 列名差异)'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_report where run_id='$NEWEST'" 2>/dev/null || docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_report"
echo '== 8. control-app 关键日志（09:25Z 起） =='
docker logs deploy-control-app-1 --since 2026-09-14T09:25:00Z 2>&1 | grep -iE 'webhook|incident|rca|investigat|model|ERROR' | head -60
echo '== 9. AM 投递日志（近 90m，成功/失败都要） =='
docker logs deploy-alertmanager-1 --since 90m 2>&1 | grep -iE 'notify|error|integration|webhook' | head -40
echo '== 10. control-app 完整日志近 90m 头尾概览（不放过滤词，看 intake 拒收痕迹） =='
docker logs deploy-control-app-1 --since 90m 2>&1 | tail -80
