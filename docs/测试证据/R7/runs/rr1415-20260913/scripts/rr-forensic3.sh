#!/bin/sh
# rr-forensic3.sh —— litellm 侧 403 定位：请求模型名/键别名；run2 states 复核
echo "== litellm 日志 08:54-08:58 窗（run1 403 面）=="
docker logs litellm-am3 --since 2026-09-13T08:54:00 --until 2026-09-13T08:58:30 2>&1 | grep -iE '403|rr14|model|key' | grep -v 'GET /' | tail -25
echo "== run2 模型调用明细 =="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select state, coalesce(requested_model,'-'), coalesce(error_code,'-') from rca_model_call where run_id='be274179-d1aa-4253-a454-d83d25c44059' order by created_at"
echo "== run1 错误码复核 =="
docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "select state, coalesce(requested_model,'-'), coalesce(error_code,'-') from rca_model_call where run_id='388d9a4d-bc0b-4eaa-882d-1c836bd7655a' order by created_at"
echo "== control-app 403 时间窗（K1 时代 vs K2 时代）=="
docker logs rriso-control-app-1 2>&1 | grep -E 'HTTP 40[13]' | tail -6
echo "== iso env AGENT_MODEL 面（脱敏：只看模型名行）=="
grep -E '^AGENT_MODEL=|^AGENT_MODEL_FALLBACK=|^OPENAI_COMPAT' /opt/build/pr/rr-iso/rr-iso.env
echo done
