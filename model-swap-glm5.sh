#!/bin/sh
# 模型切换：qwen3-max-preview → glm-5（B 批真窗最后杠杆；原值备份留证）
cd /opt/build/pr/deploy
grep -E '^AGENT_MODEL=' .env >> /opt/build/.env.model-backup-20260913 2>/dev/null
grep -v '^AGENT_MODEL=' .env > .env.tmp || true
echo 'AGENT_MODEL=glm-5' >> .env.tmp
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep AGENT_MODEL='
exit 0
