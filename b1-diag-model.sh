#!/bin/sh
# 用户指令：GLM-5 额度耗尽→换模型。先诊断：当前模型路由 / 换模脚本机制 / 流量面为何无错误行
echo "== 1) control-app 当前模型 env =="
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E "AGENT_MODEL|OPENAI_COMPAT|MODEL" || echo "(无模型 env)"
echo "== 2) deploy/.env 的模型键 =="
grep -E "AGENT_MODEL|MODEL|OPENAI" /opt/build/pr/deploy/.env 2>/dev/null | sed 's/\(KEY\|TOKEN\|SECRET\)=.*/\1=***/' || echo "(.env 无模型键)"
echo "== 3) 换模脚本（model-swap-glm5.sh 的机制与来源模型）=="
cat /opt/build/model-swap-glm5.sh
echo "== 4) r7-operator-env.sh（A0 驱动用的面）=="
sed 's/\(KEY\|TOKEN\|SECRET\|BEARER\)=.*/\1=***/' /opt/build/r7-operator-env.sh
echo "== 5) 流量面：load-generator / 业务容器状态 =="
docker ps -a --format '{{.Names}}\t{{.Status}}' | grep -Ei "load|locust|checkout|payment|frontend" || echo "(无匹配容器)"
echo "== 6) 最近 5 分钟 checkout 请求流量（Prometheus）=="
docker exec deploy-prometheus-1 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=sum(rate(rpc_server_requests_total%7Brpc_method%3D%22PaymentService%2FCharge%22%7D%5B2m%5D))' 2>/dev/null | head -c 400 || echo "(prom 查询失败)"
