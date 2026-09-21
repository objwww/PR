#!/bin/sh
echo '---chaos-admin networks---'
docker inspect alert-arena-chaos-admin-1 --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null
echo '---flagd-admin container---'
docker ps --format '{{.Names}}' | grep -i flagd
echo '---flagd networks---'
docker inspect flagd-admin-am3 --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null
echo '---probe via full names on alert-net---'
docker run --rm --network alert-net busybox:latest sh -c 'wget -q -O- --timeout=3 http://alert-arena-chaos-admin-1:8080/actuator/health 2>&1 | head -c 80; echo; wget -q -O- --timeout=3 http://flagd-admin-am3:8081/actuator/health 2>&1 | head -c 80' 2>&1
