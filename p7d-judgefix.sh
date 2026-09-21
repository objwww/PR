#!/bin/sh
# P6-judge 修复链：源码同步→重建部署→std worker 补 judge 三键（原配置无损）→预清场→验证轮就绪
set -e
cd /opt/build/pr
echo '02894db6b1a23b0822754e5d8e5acb74  /tmp/sync2.tar.gz' | md5sum -c -
tar xzf /tmp/sync2.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -4
cd deploy
docker compose build control-app web 2>&1 | grep -cE 'DONE'
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health

# ---------- std worker 原配置重建 + judge 三键注入 ----------
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std.env
if grep -q '"judge":' /tmp/std.env; then
  echo 'judge already configured in std worker'
else
  sed -i 's|{"app":{"alert":{"eval":{|&"judge":{"base-url":"'"$JUDGE_BASE"'","api-key":"'"$JUDGE_KEY"'","model":"deepseek-v3"},|' /tmp/std.env
  echo 'judge keys injected'
fi
grep -o '"judge":{[^}]*}' /tmp/std.env | sed -E 's/(api-key..)...*/\1**/'
docker rm -f eval-worker-std >/dev/null 2>&1
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std.env \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
grep -E '评测执行注册表装载|Started ControlApplication|ERROR' /tmp/$(docker inspect eval-worker-std --format '{{.LogPath}}' | xargs basename 2>/dev/null) 2>/dev/null || docker logs eval-worker-std 2>&1 | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -3

# ---------- 预清场：S3/S4/S5 incident 状态 ----------
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key in ('alertname=ArenaDuplicateOrders|service=order-arena|job=order-arena','alertname=ArenaIllegalTransitions|service=order-arena|job=order-arena','alertname=ArenaOrderStuck|service=order-arena|job=order-arena');"
