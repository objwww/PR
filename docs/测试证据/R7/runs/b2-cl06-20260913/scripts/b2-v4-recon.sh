#!/bin/sh
echo "== running drivers =="
pgrep -af "e2e-b2-cl06|b2-cl06-retry" || echo none
echo "== latest b2 logs =="
ls -lt /opt/build/pr-logs/ | grep -i b2 | head -10
echo "== last a0 log tail =="
L=$(ls -t /opt/build/pr-logs/b2-cl06-a0-*.log 2>/dev/null | head -1)
echo "file=$L"
[ -n "$L" ] && tail -12 "$L"
echo "== retry wrapper log tail =="
for f in $(ls -t /opt/build/pr-logs/*b2-cl06-retry* /opt/build/pr-logs/*retry*v4* 2>/dev/null | head -2); do echo "-- $f"; tail -8 "$f"; done
echo "== flagd injection command source =="
grep -l "paymentFailure" /opt/build/*.sh 2>/dev/null || echo none
echo "== orchestrator scripts on box =="
ls -t /opt/build/*.sh | head -15
echo "== override md5 (195 copy) =="
md5sum /opt/build/b2-cl06-override.yml 2>/dev/null || echo missing
echo "== container env now =="
docker exec deploy-control-app-1 printenv APP_ALERT_R7_INPUTCAPTURE APP_ALERT_R7_PRIMARY_MAX_DELEGATION_BATCHES 2>/dev/null || echo env-missing
echo "== last run id =="
cat /tmp/b2cl06/run-id.txt 2>/dev/null || echo none
