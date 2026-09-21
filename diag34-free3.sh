#!/bin/sh
# 诊断 403：对比 /diag 与 /diag/free 的响应头；确认部署副本内容
RID_INC=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.incident_id from rca_run r where r.state='SUCCEEDED' limit 1" | tr -d '[:space:]')
curl -s -b /tmp/d34.cookie -c /tmp/d34.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/d34.cookie | awk '{print $NF}')
printf '{"key":"impact","createdBy":"human:operator"}' > /tmp/d34-req1.json
printf '{"question":"test","createdBy":"human:operator"}' > /tmp/d34-req2.json
echo '=== /diag POST 响应头 ==='
curl -s -b /tmp/d34.cookie -D - -o /dev/null -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req1.json | head -8
echo '=== /diag/free POST 响应头 ==='
curl -s -b /tmp/d34.cookie -D - -o /dev/null -X POST "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' --data-binary @/tmp/d34-req2.json | head -8
echo '=== /diag/free GET（应 405 证路由存在） ==='
curl -s -b /tmp/d34.cookie -o /dev/null -w 'get_free=%{http_code}\n' "http://127.0.0.1:8080/api/v1/incidents/$RID_INC/diag/free"
echo '=== 部署副本 free 方法上的注解检查 ==='
grep -n -B3 'free(' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/alert/interfaces/DiagSessionController.java | head -12
grep -c 'PreAuthorize' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/alert/interfaces/DiagSessionController.java || true
