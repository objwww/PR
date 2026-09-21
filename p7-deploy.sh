#!/bin/sh
set -e
cd /opt/build/pr
echo 'fab9a3d190845a101117782e3200b36c  /tmp/p7-batch.tar.gz' | md5sum -c -
tar xzf /tmp/p7-batch.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
# judge 环境写入 worker 启动脚本（模型面=litellm-am3 代理，alert-net 可达已探明）
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
if grep -q 'judge.base-url' /tmp/p4w.sh; then
  echo 'judge env already in p4w.sh'
else
  sed -i "s|\"grader-version\":|\"judge.base-url\":\"$JUDGE_BASE\",\"judge.api-key\":\"$JUDGE_KEY\",\"judge.model\":\"qwen3-max\",\"grader-version\":|" /tmp/p4w.sh
  echo 'judge env injected into p4w.sh'
fi
# worker 切 rt-v4 + gate 白名单加 rt-v4
sed -i 's/"dataset-version":"rt-v3"/"dataset-version":"rt-v4"/' /tmp/p4w.sh
grep -o '"dataset-version":"[^"]*"' /tmp/p4w.sh
if grep -q '^APP_EVAL_LAUNCH_DATASET_VERSIONS=' .env; then
  sed -i 's|^APP_EVAL_LAUNCH_DATASET_VERSIONS=.*|APP_EVAL_LAUNCH_DATASET_VERSIONS=eval-ds-1,rt-v2,rt-v3,rt-v4|' .env
else
  echo 'APP_EVAL_LAUNCH_DATASET_VERSIONS=eval-ds-1,rt-v2,rt-v3,rt-v4' >> .env
fi
grep '^APP_EVAL_LAUNCH_DATASET_VERSIONS=' .env
docker compose build control-app web 2>&1 | grep -cE 'DONE'
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- V145/V146 核对 ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select count(*) from eval_case_judge;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select dv.version, cv.case_key, position('rt3-' in cv.payload#>>'{rawArtifact,adversarial_payload_json}') > 0 as fresh_fp, cv.payload#>'{rawArtifact,gt_panel}' from case_version cv join dataset_version dv on dv.id=cv.dataset_version_id where dv.name='redteam-ds' and cv.valid_to is null order by cv.case_key;"
echo '--- 重启 worker（rt-v4 + judge）---'
docker rm -f eval-worker-p4rt >/dev/null 2>&1 || true
sh /tmp/p4w.sh
