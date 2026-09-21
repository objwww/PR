#!/bin/sh
# 步数实验：8 → 15（max-steps 与 STEP 预算同步；原值备份留证）
cd /opt/build/pr/deploy
grep -E '^SPRING_APPLICATION_JSON=' .env >> /opt/build/.env.step-backup-20260913 2>/dev/null
grep -v '^SPRING_APPLICATION_JSON=' .env > .env.tmp || true
echo 'SPRING_APPLICATION_JSON={"app":{"alert":{"r7":{"primary":{"max-steps":15}},"am4":{"budget":{"step":15}}}}}' >> .env.tmp
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c SPRING_APPLICATION_JSON'
exit 0
