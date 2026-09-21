#!/bin/sh
echo '--- checkout 事故起点与代数 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(id::text,8)||' | started='||episode_started_at||' | gen='||generation||' | '||status from incident where incident_key like 'alertname=checkout%' order by episode_started_at desc limit 2;"
echo '--- Prometheus 当前 checkout 告警规则状态 ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/alerts' | grep -o '{\"labels\":{\"alertname\":\"checkout\"[^}]*}' | head -2" || true
echo '--- flagd 当前 paymentFailure 值（内存态经文件核对）---'
grep -A 3 '"paymentFailure"' /opt/build/opentelemetry-demo/src/flagd/demo.flagd.json | head -5
echo '--- 最近 payment/checkout 错误率（近10分钟）---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=checkout_requests_err_rate' | head -c 300" || true
