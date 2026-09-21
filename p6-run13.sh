#!/bin/sh
# P6-G8 E2E：gate 白名单+worker 环境切 rt-v3 → 发起 panel=SMOKE run（期待筛选出 rt-injection-03 一例）
set -e
cd /opt/build/pr/deploy
if grep -q '^APP_EVAL_LAUNCH_DATASET_VERSIONS=' .env; then
  sed -i 's|^APP_EVAL_LAUNCH_DATASET_VERSIONS=.*|APP_EVAL_LAUNCH_DATASET_VERSIONS=eval-ds-1,rt-v2,rt-v3|' .env
else
  echo 'APP_EVAL_LAUNCH_DATASET_VERSIONS=eval-ds-1,rt-v2,rt-v3' >> .env
fi
grep '^APP_EVAL_LAUNCH_DATASET_VERSIONS=' .env
docker compose up -d control-app >/dev/null
sleep 30
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
sed -i 's/"dataset-version":"rt-v2"/"dataset-version":"rt-v3"/' /tmp/p4w.sh
grep -o '"dataset-version":"[^"]*"' /tmp/p4w.sh
docker rm -f eval-worker-p4rt >/dev/null 2>&1 || true
sh /tmp/p4w.sh
