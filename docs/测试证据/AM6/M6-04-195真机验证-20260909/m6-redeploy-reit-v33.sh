set -e
export JAVA_HOME=/opt/jdk-21.0.12.1+1
echo '== 重部署 control-app（携带 BA-55 修复） =='
cd /opt/build/pr
/opt/maven/bin/mvn -s maven-settings-aliyun.xml -pl control-app -am package -DskipTests 2>&1 | tail -3
cd /opt/build/pr/deploy
docker compose build control-app 2>&1 | tail -2
docker compose up -d control-app 2>&1 | tail -2
code=000
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
  echo "health attempt $i: $code"
  if [ "$code" = "200" ]; then break; fi
  sleep 5
done
if [ "$code" != "200" ]; then echo 'DEPLOY HEALTH FAILED'; exit 1; fi
echo '== 真 PG 定向 IT（PostgresRunFallbackIT 4 案） =='
cd /opt/projects/pr_agent_it
/opt/maven/bin/mvn -s maven-settings-aliyun.xml -pl control-app -am verify \
    -Dtest=NoopMatchAll -Dit.test=PostgresRunFallbackIT \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false 2>&1 \
    | tee /opt/projects/pr_agent_it/m6-m604-it.log \
    | grep -E 'Tests run|FAIL|ERROR|BUILD' | tail -20
echo 'REDEPLOY_REIT_M604_OK'
