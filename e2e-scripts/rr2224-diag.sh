#!/bin/sh
# RR22/23/24 mock=0 诊断（只读）：容器/env/访问日志/应用日志/容器到宿主机 mock 的 TCP 连通性
echo ==containers==
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'rriso|litellm'
echo ==app-env==
docker exec rriso-control-app-1 sh -c 'printenv | grep -E "OPENAI_COMPAT|PER_CALL|AGENT_MODEL|FINGERPRINT"'
echo ==mocklog==
wc -l /opt/build/pr-logs/rr2224/mock-access.log 2>/dev/null
tail -3 /opt/build/pr-logs/rr2224/mock-access.log 2>/dev/null
echo ==applog-net-errors==
docker logs rriso-control-app-1 --since 25m 2>&1 | grep -iE 'refus|timed out|unknownhost|unreachable|connect' | tail -10
echo ==tcp-from-container==
docker exec rriso-control-app-1 bash -c 'timeout 3 bash -c "cat < /dev/null > /dev/tcp/172.28.0.1/18100" 2>/dev/null && echo TCP-18100-OK || echo TCP-18100-FAIL' 2>/dev/null || echo no-bash
echo ==host-listen==
ss -ltn | grep -E '18100|18443'
echo ==iptables-input==
iptables -S INPUT 2>/dev/null | head -10
iptables -L INPUT -n 2>/dev/null | grep -iE 'REJECT|DROP' | head -5
echo ==rriso-net-members==
docker network inspect rriso-net --format '{{range .Containers}}{{.Name}} {{end}}'
