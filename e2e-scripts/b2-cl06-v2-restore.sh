#!/bin/sh
# b2-cl06-v2-restore.sh —— BA-141 列名修正取证 + 窗口复原（B2-1 同款姿态）
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
r=3564fee0-236f-4fb8-87c7-b17a197f86b4
echo "== rca_report 列："
G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_report'"
echo "== rca_primary_checkpoint 列："
G "select string_agg(column_name,',') from information_schema.columns where table_name='rca_primary_checkpoint'"
echo "== 报告内容（前 300 字）："
G "select left(regexp_replace(coalesce(payload::text,content::text,'(null)'),'[\" \\t\\n]+',' ','g'),300) from rca_report where run_id='$r'"
echo "== 复原：撤 override 重建 =="
cd /opt/build/pr/deploy
docker compose -p deploy -f docker-compose.yml up -d control-app 2>&1 | tail -2
i=0; HC=000
while [ $i -lt 18 ]; do
  HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)
  [ "$HC" = "200" ] && break
  sleep 5; i=$((i+1))
done
echo "health=$HC"
echo "== 复原验证：容器 env 面应无 v5 键 =="
docker exec deploy-control-app-1 env | grep -E 'INPUTCAPTURE|MAX_DELEGATION|PRIMARY_PROMPT' || echo "env 干净（无 INPUTCAPTURE/MAX_DELEGATION/PRIMARY_PROMPT）"
echo "== flagd 复原 off =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'off'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
echo "== 复原后缺省姿态抽查（MAX_DELEGATION 经 .env=0 生效即零委派姿态） =="
docker exec deploy-control-app-1 env | grep -E 'APP_ALERT_R7_PRIMARY_MAX_DELEGATION|APP_ALERT_R7_INPUTCAPTURE' || echo "（两键均不在容器 env——.env 键无 compose 映射=惰性，零委派由镜像缺省/运行态配置承载）"
