#!/bin/sh
# rr-iso-prep1.sh —— 隔离栈前置勘察：模型路由键名/litellm 模型面/admin 可达
cd /opt/build/pr/deploy
echo "== .env 模型路由键名与非秘密值："
grep -E '^AGENT_MODEL|^APP_ALERT' .env | grep -vE 'KEY|SECRET|TOKEN|PASS' | head -15
echo "== control-app 容器实际模型 env（键名+非秘密值）："
docker exec deploy-control-app-1 env | grep -E '^AGENT_MODEL' | grep -v 'KEY' | sort
echo "== litellm 模型清单（经 admin）："
docker exec litellm-am3 sh -c 'curl -s localhost:4000/v1/models -H "Authorization: Bearer $LITELLM_MASTER_KEY"' | head -c 600
echo ""
echo "== litellm key/generate 面探测（不实际生成——仅探测 405/200）："
docker exec litellm-am3 sh -c 'curl -s -o /dev/null -w %{http_code} localhost:4000/key/generate -X POST -H "Authorization: Bearer $LITELLM_MASTER_KEY" -H "Content-Type: application/json" -d "{}"'
echo ""
echo "== standing control-app 重启计数（RR14 不变量基线）："
docker inspect deploy-control-app-1 --format 'restarts={{.RestartCount}} started={{.State.StartedAt}}'
curl -s -o /dev/null -w 'standing-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
