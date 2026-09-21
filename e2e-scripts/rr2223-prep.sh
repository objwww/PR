#!/bin/sh
# rr2223-prep.sh —— RR22/23/24 注入面预研：网关可达性/loki 配置/per-call timeout/模型路由 env
echo "== rriso-net 网关 IP（容器→宿主 mock 通道）=="
GW=$(docker network inspect rriso-net --format '{{(index .IPAM.Config 0).Gateway}}')
echo "gateway=$GW"
echo "== 容器内可达宿主端口试探（宿主 18100 暂听）=="
(python3 -c "
import socket,time
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR,1); s.bind(('0.0.0.0',18100)); s.listen(8)
time.sleep(12); s.close()
" &) 2>/dev/null
sleep 1
docker exec rriso-control-app-1 sh -c "wget -q -T 3 -O - http://$GW:18100/ping 2>&1 | head -c 60" 2>/dev/null || docker exec rriso-control-app-1 curl -s -m 3 "http://$GW:18100/ping" 2>&1 | head -c 60 || echo "容器无 wget/curl——改用 python/nc 探测"
echo
echo "== control-app 模型/loki 相关 env（值脱敏）=="
docker exec rriso-control-app-1 env | grep -iE 'LOKI|OPENAI_COMPAT|AGENT_MODEL|PROMETHEUS|ALERT_EVAL' | sed -E 's/(KEY|TOKEN|PASSWORD)=.*/\1=***/' | sort
echo "== per-call timeout 配置键探测（本地代码面）=="
grep -rn 'perCallTimeout\|per-call' /opt/build/pr/control-app/src/main/java --include=*.java -l 2>/dev/null | head -3
echo "== loki 工具执行器配置键 =="
grep -rn 'loki' /opt/build/pr/control-app/src/main/resources/application*.yml 2>/dev/null | head -6
grep -rniE 'loki.*(url|base)' /opt/build/pr/control-app/src/main/java --include=*.java -l 2>/dev/null | head -4
echo done
