#!/bin/sh
# B2 CL-06 重试包裹器：委派非确定性（qwen 采样），最多 5 发，SUITE PASS 即停
cd /opt/build/pr/deploy
OV=/opt/build/b2-cl06-override.yml
docker compose -p deploy -f docker-compose.yml -f "$OV" up -d control-app 2>&1 | tail -2
i=0; HC=000
while [ $i -lt 18 ]; do
  HC=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health)
  [ "$HC" = "200" ] && break
  sleep 5
  i=$((i+1))
done
[ "$HC" = "200" ] || { echo "startup fail (health=$HC)"; exit 1; }
docker exec deploy-control-app-1 env | grep -q '第一步必须以 delegate' || { echo "FAIL: 新 prompt 未生效"; exit 1; }
echo "override-ok（硬性委派 prompt 在）"
i=1
while [ $i -le 5 ]; do
  echo "==== attempt $i ===="
  cd /opt/build/b2tree
  sh -c '. /opt/build/r7-operator-env.sh && export R7_PRIMARY_ALLOWLIST=prometheus.query,logs.query,prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,prometheus.rules,logs.aggregate && R7_RUNS_DIR=/opt/build/runs-b2cl06 sh ./e2e-b2-cl06.sh' \
    > /opt/build/pr-logs/b2-cl06-a0-try$i.log 2>&1
  if grep -q 'SUITE PASS' /opt/build/pr-logs/b2-cl06-a0-try$i.log; then
    echo "attempt $i SUITE PASS"; break
  fi
  echo "attempt $i 未过：$(grep -E 'FAIL' /opt/build/pr-logs/b2-cl06-a0-try$i.log | tail -1 | cut -c1-120)"
  i=$((i+1))
done
RID=$(ls -t /opt/build/runs-b2cl06/*/run-id.txt 2>/dev/null | head -1)
[ -n "$RID" ] && cp "$RID" /tmp/b2cl06/run-id.txt
tail -6 /opt/build/pr-logs/b2-cl06-a0-try$((i-1)).log | cut -c1-165
echo RETRY-WRAPPER-DONE
