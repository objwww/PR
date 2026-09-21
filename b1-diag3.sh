#!/bin/sh
# 模型 probe + 流量真实计数 + 指标面故障传导
KEY=$(docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^AGENT_MODEL_API_KEY=' | cut -d= -f2-)
echo "== 1) qwen3-max-preview 探针（1 token）=="
docker exec deploy-control-app-1 sh -c "wget -qO- --header='Authorization: Bearer $KEY' --header='Content-Type: application/json' --post-data='{\"model\":\"qwen3-max-preview\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":4}' http://litellm-am3:4000/v1/chat/completions" | head -c 500
echo
echo "== 2) Loki 计数（近 5m，count_over_time）=="
for svc in checkout payment frontend; do
  N=$(docker exec deploy-loki-1 wget -qO- "http://127.0.0.1:3100/loki/api/v1/query?query=sum(count_over_time(%7Bservice_name%3D%22$svc%22%7D%5B5m%5D))" 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);r=d['data']['result'];print(r[0]['value'][1] if r else 0)" 2>/dev/null || echo ERR)
  echo "  $svc lines(5m)=$N"
done
echo "== 3) payment WARN/error 行计数（故障传导的日志面）=="
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query?query=sum(count_over_time(%7Bservice_name%3D%22payment%22%2Cdetected_level%3D~%22warn%7Cerror%22%7D%5B5m%5D))' 2>/dev/null | head -c 300
echo
echo "== 4) Prom 指标面（原始响应头 300 字）=="
docker exec deploy-prometheus-1 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count' 2>&1 | head -c 300
echo
echo "DIAG3-DONE"
