#!/bin/sh
# rr1415-survey.sh —— OR-04 凭据面勘察（零值打印：只打名/存在性/长度指纹）
cd /opt/build/pr/deploy
echo "== .env 凭据类键名（值只打长度 sha8）："
awk -F= '/KEY|SECRET|PASS|TOKEN|CRED/ && !/^#/ && NF>1 { printf "%s len=%d sha8=%s\n", $1, length($2), substr(system("printf %s \"" $2 "\" | sha256sum | cut -c1-8"),0,0) }' .env 2>/dev/null | cut -d' ' -f1 | sort
echo "---重试（纯键名列表）："
grep -E '^[A-Z_]*(KEY|SECRET|PASS|TOKEN|CRED)[A-Z_]*=' .env | cut -d= -f1
echo "== compose 里消费这些键的服务面："
grep -nE 'AGENT_MODEL|_KEY|SECRET|PASSWORD' docker-compose.yml | head -20
echo "== 模型网关调用面（auth header 形态）："
grep -rn 'Authorization\|x-api-key\|Bearer' /opt/build/pr/control-app/src/main/java --include=*.java -l | head -5
echo "== 网关 am3 容器与 key 管理面："
docker ps --format '{{.Names}}' | grep -iE 'am3|gateway|qwen|model' | head -5
echo "== control-app 启动必须项探测（配置类 required/validate）："
grep -rn 'AGENT_MODEL_API_KEY' /opt/build/pr/control-app/src/main/java --include=*.java | head -5
grep -rn 'AGENT_MODEL_API_KEY' docker-compose.yml | head -3
