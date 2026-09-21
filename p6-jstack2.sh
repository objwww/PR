#!/bin/sh
NSPID=$(docker inspect eval-worker-std --format '{{.State.Pid}}')
echo "ns pid=$NSPID"
/usr/bin/timeout 30 /opt/jdk-21.0.12.1+1/bin/jstack $NSPID 2>&1 | grep -B2 -A16 'ArenaChaosScenarioDriver\|PrometheusAlertProbe\|RcaRunResolver' | head -70
