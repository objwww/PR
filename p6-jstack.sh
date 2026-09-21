#!/bin/sh
PID=$(docker inspect eval-worker-std --format '{{.State.Pid}}')
echo "host pid=$PID"
/usr/bin/timeout 20 jstack $PID 2>/dev/null | grep -A 12 'rcaRunResolver\|RcaRunResolver\|awaitAllResolved\|AlertProbe\|deactivate' | head -40
echo '---fallback: jstack via docker---'
docker exec eval-worker-std sh -c 'jstack 1 2>/dev/null || /opt/java/openjdk/bin/jstack 1' 2>/dev/null | grep -B2 -A14 'ArenaChaosScenarioDriver\|RcaRunResolver\|PrometheusAlertProbe' | head -60
