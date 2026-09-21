#!/bin/sh
set -e
# 3.15 正式烟测：整改项写面（先 GET 铸新 CSRF 令牌，再取用）
J=/tmp/p315.cookie
mint() { curl -s -b $J -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf; grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}'; }
echo '=== 重新登录（部署重启后内存会话失效） ==='
rm -f $J
curl -s -c $J -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J -c $J -o /dev/null -w 'login=%{http_code}\n' -X POST http://127.0.0.1:8080/api/auth/login -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'username=operator' --data-urlencode 'password=Tmp#p315-0916'
echo '=== 清理探针污染（action_items probe-* 与 catalog probe-svc owner） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "delete from action_items where title like 'probe-%';" 
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "delete from service_owner where service_name = 'probe-svc';"
INC=$(curl -s -b $J 'http://127.0.0.1:8080/api/v1/postmortems' | grep -oE '"incidentId":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
echo "INC=$INC"
T=$(mint)
echo '=== 烟测1：登记两条真实整改项（created_by=会话真值） ==='
curl -s -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b $J -c $J -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"title":"治理 order-service 下游重试超时阈值","owner":"checkout-oncall"}'; echo
T=$(mint)
curl -s -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" -b $J -c $J -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"title":"inventory-service 增加熔断兜底","owner":""}'; echo
echo '=== 烟测2：清单读回 + 闭环派生（2 项全 OPEN → closed=false） ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items"; echo
ITEM=$(curl -s -b $J "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" | grep -oE '"id":"[0-9a-f-]+"' | head -1 | cut -d'"' -f4)
T=$(mint)
echo "=== 烟测3：闭环切换 $ITEM → DONE ==="
curl -s -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items/$ITEM/toggle" -b $J -c $J -H "X-XSRF-TOKEN: $T"; echo
curl -s -b $J "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items" | grep -oE '"closed":[a-z]+|"doneCount":[0-9]+'
echo '=== 烟测4：切换回 OPEN（状态可逆验证）后保持 DONE 供截图演示 ==='
T=$(mint)
curl -s -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items/$ITEM/toggle" -b $J -c $J -H "X-XSRF-TOKEN: $T"; echo
T=$(mint)
curl -s -X POST "http://127.0.0.1:8080/api/v1/postmortems/$INC/action-items/$ITEM/toggle" -b $J -c $J -H "X-XSRF-TOKEN: $T"; echo
echo '=== 烟测5：复盘详情调查口径（单事故） ==='
curl -s -b $J "http://127.0.0.1:8080/api/v1/postmortems/$INC" | grep -oE '"investigation":\{[^}]*\}'
echo '=== SQL 对账：action_items 落库真值 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state, count(*), coalesce(string_agg(title, ' | '), '') from action_items where incident_id = '$INC' group by state order by state;"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select created_by, owner, done_at is not null as has_done_at from action_items where incident_id = '$INC' order by created_at;"
docker logs deploy-control-app-1 --since 5m 2>&1 | grep -c 'APPLICATION FAILED' || true
