#!/bin/sh
# 换模落点定位 + 流量面复核
KEY=$(docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^AGENT_MODEL_API_KEY=' | cut -d= -f2-)
echo "== 1) litellm 可用模型列表 =="
docker exec deploy-control-app-1 sh -c "wget -qO- --header='Authorization: Bearer $KEY' http://litellm-am3:4000/v1/models 2>/dev/null || wget -qO- --header='Authorization: Bearer $KEY' http://litellm-am3:4000/models 2>/dev/null" | head -c 1200
echo
echo "== 2) .env 模型备份内容（glm-5 之前的值）=="
cat /opt/build/.env.model-backup-20260913 2>/dev/null || echo "(无备份文件)"
echo "== 3) a0 e2e 脚本/发布体里的模型钉定 =="
grep -rn "glm-5\|glm5\|GLM" /opt/build/pr/docs/测试证据/R7/e2e-脚本/e2e-r7-a0-provider-receipt-chain.sh 2>/dev/null | head -5
grep -l "glm" /opt/build/runs-r7batch3/*/publish-a0.body 2>/dev/null | tail -2
grep -o '"model"[^,}]*' $(ls -t /opt/build/runs-r7batch3/*/publish-a0.body 2>/dev/null | head -1) 2>/dev/null | head -3
echo "== 4) Loki：checkout 任意行量（近 5m）与 payment 行量 =="
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D&limit=1&since=5m' 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);r=d['data']['result'];print('checkout series:',len(r),'lines:',sum(len(s['values']) for s in r))" 2>/dev/null
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%7D&limit=1&since=5m' 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);r=d['data']['result'];print('payment series:',len(r),'lines:',sum(len(s['values']) for s in r))" 2>/dev/null
echo "== 5) Prom：rpc_client error_type=UNKNOWN 当前值（故障传导面）=="
docker exec deploy-prometheus-1 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%2Crpc_method%3D%22PaymentService%2FCharge%22%7D' 2>/dev/null | python3 -c "import sys,json;d=json.load(sys.stdin);[print(s['metric'].get('service_name','?'),'=',s['value'][1]) for s in d['data']['result']]" 2>/dev/null || echo "(查询失败)"
echo "DIAG2-DONE"
