#!/bin/sh
# E2E 步骤1：确认 webhook bearer 变量名（只回显变量名与长度，不回显值）
docker exec deploy-control-app-1 sh -c 'env | grep -E "BEARER" | sed -E "s/=(.{0,4}).*/=\1…(len masked)/"'
echo '--- AM webhook 配置 ---'
docker exec alertmanager-am0 sh -c 'cat /etc/alertmanager/alertmanager.yml 2>/dev/null || find / -name "alertmanager*.yml" 2>/dev/null | head -3' | grep -v -i token
echo '--- control webhook 端点路径（从 jar 里 grep 映射） ---'
docker logs deploy-control-app-1 2>&1 | grep -i "Mapped.*webhook" | head -5
exit 0
