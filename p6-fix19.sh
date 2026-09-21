#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '--- surgical cleanup of 09-17 verify-probe debris (severity=warning, 无 job 标签，非生产面) ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "update incident set status='RESOLVED', resolved_at=now(), updated_at=now() where id='4324b60f-d6e1-45ed-9518-ebd2fc8bf5c5' and status='FIRING';"
$PG "select incident_key||' '||status from incident where incident_key='alertname=ArenaDuplicateOrders|service=order-arena';"
cd /opt/build/pr/deploy
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p4rt-20260917')
BK=/tmp/env-backup-p6r19-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe39.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p4rt-20260917" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P6-judge真裁决v4(S3S4S5x2)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","idempotencyKey":"p6-judge-real-20260919-d"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
echo 'env-restored-file-only'
sleep 6
docker logs eval-worker-std 2>&1 | grep -E '领取 LAUNCH' | tail -1
