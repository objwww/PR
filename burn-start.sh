#!/bin/sh
# 持续真实烧损流量（SAFE 关闭面之外的既有生产链路；BA-20 口径的真实 firing 源）：
# 空购物车结账 → checkout gRPC 非 OK → SLO 烧损 → checkout page/ticket 告警
# 不触碰 flagd / drill / eval 注入面。停止：docker rm -f burn-generator
NET=$(docker inspect frontend --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}')
docker rm -f burn-generator 2>/dev/null || true
docker run -d --name burn-generator --network "$NET" curlimages/curl:8.8.0 sh -c '
i=0
while :; do
  i=$((i+1))
  curl -s -m 3 -o /dev/null -X POST http://frontend:8080/api/checkout \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"burn-'$i'\",\"email\":\"burn@example.com\",\"address\":{\"streetAddress\":\"burn\",\"zipCode\":\"00000\",\"city\":\"burn\",\"state\":\"BN\",\"country\":\"burn\"},\"userCurrency\":\"USD\",\"creditCard\":{\"creditCardNumber\":\"4432-8015-6152-0454\",\"creditCardExpirationMonth\":1,\"creditCardExpirationYear\":2039,\"creditCardCvv\":123}}"
  sleep 0.2
done'
echo "burn-generator started on $NET"
sleep 20
echo '== 20s 后 checkout 非 OK 速率（应显著 > 基线 0.049） =='
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=sum(rate(rpc_server_call_duration_seconds_count{service_name="checkout"}[5m]))' | head -c 220; echo
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=sum(rate(rpc_server_call_duration_seconds_count{service_name="checkout",rpc_response_status_code!="OK"}[5m]))' | head -c 220; echo
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=sum(increase(rpc_server_call_duration_seconds_count{service_name="checkout",rpc_response_status_code!="OK"}[2m]))' | head -c 220; echo
