#!/bin/sh
set -e
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#ev09v-20260917')
BK=/tmp/env-backup-ev09v-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe18.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#ev09v-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
echo '=== GET /api/eval/runs（stability 面）==='
curl -s -b $J 'http://127.0.0.1:8080/api/eval/runs?limit=3' | python3 -c "
import json,sys
d=json.load(sys.stdin)
for it in d['items']:
    s=it.get('stability')
    print(it['runId'][:8], it.get('state'),
          'stability=', json.dumps(s, ensure_ascii=False))
"
RID=$(curl -s -b $J 'http://127.0.0.1:8080/api/eval/runs?limit=1' | python3 -c "import json,sys;print(json.load(sys.stdin)['items'][0]['runId'])")
echo '=== GET /api/eval/runs/{id}（详情 stability）==='
curl -s -b $J "http://127.0.0.1:8080/api/eval/runs/$RID" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('detail stability=', json.dumps(d.get('stability'), ensure_ascii=False))
print('detail quality.e2e=', json.dumps(d.get('quality',{}).get('endToEndHitRate'), ensure_ascii=False))
"
echo '=== 直接 SQL 对拍（同 run 轮次聚合真值）==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "
with t as (
  select eval_run_id, scenario_id,
         count(*) as rounds,
         count(*) filter (where verdict='DECIDABLE' and root_cause_hit) as hits,
         count(distinct verdict) as dv,
         count(distinct coalesce(actual_root_cause::text,'~null~')) as da
    from eval_case_result
   where eval_run_id='$RID'
   group by eval_run_id, scenario_id)
select 'rounds='||sum(rounds)||' hits='||sum(hits)||
       ' scenarios='||count(*)||
       ' allhit='||count(*) filter (where hits=rounds)||
       ' consistent='||count(*) filter (where dv<=1 and da<=1)
  from t;"
cp "$BK" .env
A=$(md5sum < .env | cut -d' ' -f1); B=$(md5sum < "$BK" | cut -d' ' -f1)
[ "$A" = "$B" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
sleep 28
curl -s -o /dev/null -w 'final-health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
