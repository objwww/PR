#!/bin/sh
curl -s -o /dev/null -w 'health-now=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '=== 最近账本相关日志 ==='
docker logs deploy-control-app-1 --since 8m 2>&1 | grep -iE 'diag-chat|PENDING 写失败|DuplicateKey|DataIntegrity|ERROR' | tail -12
