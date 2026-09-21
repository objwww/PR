#!/bin/sh
NEWTOKEN() {
  curl -s -b /tmp/p313.cookie -c /tmp/p313.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
  grep XSRF-TOKEN /tmp/p313.cookie | tail -1 | awk '{print $NF}'
}
T=$(NEWTOKEN)
echo "=== owner 登记复验（token_len=${#T}） ==="
curl -s -b /tmp/p313.cookie -X POST "http://127.0.0.1:8080/api/v1/catalog/owner" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"service":"checkout","owner":"checkout-oncall","note":"3.13 验收登记"}'; echo
echo '=== flyway ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '=== service_owner 授权 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select grantee, privilege_type from information_schema.role_table_grants where table_name='service_owner';"
