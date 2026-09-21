#!/bin/sh
echo '---arena/alert stack---'
docker ps --format '{{.Names}} {{.Status}}' | grep -iE 'prometheus|alertmanager|order-arena|arena|flagd'
echo '---probe prometheus from alert-net---'
PROM=$(docker ps --format '{{.Names}}' | grep -i prometheus | head -1)
echo "PROM=$PROM"
docker inspect $PROM --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null
echo '---prometheus query api---'
docker exec $PROM wget -q -O- --timeout=5 'http://localhost:9090/api/v1/query?query=up' 2>&1 | head -c 200
echo ''
