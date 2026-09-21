#!/bin/sh
echo '--- web 容器端口映射:'
docker port deploy-web-1
echo '--- WEB_BIND/PORT in .env:'
grep -E '^WEB_BIND|^WEB_PORT|^CONTROL_BIND|^CONTROL_PORT|^AUTH_OPERATOR' .env | sed 's/\(BCRYPT=\).*/\1<hidden>/'
echo '--- nginx 侧登录页路由:'
curl -s -o /dev/null -w 'front_8090=%{http_code}\n' http://127.0.0.1:8090/
curl -s -o /dev/null -w 'api_8080=%{http_code}\n' http://127.0.0.1:8080/actuator/health
