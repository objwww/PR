#!/bin/sh
# 告警链路只读盘点：flag 现场 / Alertmanager firing / 事件与 RCA 近况 / webhook 投递 / 模型面
echo '== 1. flagd 现场（e03 遗留位只读） =='
curl -s -m 5 http://127.0.0.1:8081/flags 2>/dev/null | head -c 400 || echo 'flagd-admin 8081 不可达'
echo
docker exec flagd sh -c 'wget -qO- http://localhost:8013/flagd.flags.v1.Service/GetFlags 2>/dev/null | head -c 300' 2>/dev/null || echo '(容器内直查不可用)'
echo
echo '== 2. Alertmanager 当前 firing =='
curl -s -m 5 http://127.0.0.1:9093/api/v2/alerts | head -c 600
echo
echo '== 3. 事件与 RCA 近况（最近 5 条） =='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select alert_fingerprint || ' | ' || state || ' | gen=' || generation || ' | ' || coalesce(current_rca_run_id::text,'no-run') || ' | ' || created_at from incident order by created_at desc limit 5"
echo '-- rca_run 最近 5 --'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.id || ' | ' || r.state || ' | ' || coalesce(r.engine,'-') || ' | ' || r.created_at from rca_run r order by r.created_at desc limit 5"
echo '== 4. webhook 投递与 intake（最近） =='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from alert_event where created_at > now() - interval '2 hours'" 2>/dev/null || true
docker logs alertmanager-am0 --since 30m 2>&1 | grep -ci error || true
echo '== 5. 模型面与 load-generator =='
docker exec deploy-control-app-1 sh -c 'env | grep -E "^AGENT_MODEL=|^OPENAI_COMPAT_BASE_URL=" | sed "s/=.*/=<set>/"'
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'load-generator|litellm|alertmanager|flagd'
echo '== 6. arena checkout 烧损现值（近 10m 错误率分子） =='
curl -s -m 5 'http://127.0.0.1:9090/api/v1/query?query=sum(rate(rpc_server_call_duration_seconds_count{service="checkout",rpc_method="PlaceOrder",rpc_grpc_status_code!="0"}[10m]))' | head -c 300
echo
