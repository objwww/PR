#!/bin/sh
NSPID=$(docker inspect eval-worker-std --format '{{.State.Pid}}')
kill -QUIT $NSPID
sleep 4
docker logs eval-worker-std --since 2m 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -B3 -A18 'ArenaChaosScenarioDriver.deactivate\|PrometheusAlertProbe\|RcaRunResolver\|resolveByIncident' | head -80 | cut -c1-160
