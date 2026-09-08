set -e
export JAVA_HOME=/opt/jdk-21.0.12.1+1
cd /opt/build/pr
/opt/maven/bin/mvn -s maven-settings-aliyun.xml -pl control-app -am package -DskipTests 2>&1 | tail -4
cd /opt/build/pr/deploy
docker compose build control-app 2>&1 | tail -3
docker compose up -d control-app 2>&1 | tail -2
code=000
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
  echo "health attempt $i: $code"
  if [ "$code" = "200" ]; then break; fi
  sleep 5
done
if [ "$code" != "200" ]; then echo 'DEPLOY HEALTH FAILED'; exit 1; fi
echo '== 确认无崩溃循环 =='
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -ciE 'APPLICATION FAILED|PlaceholderResolution' || echo '0 bad patterns'
echo 'DEPLOY_M604_OK'
