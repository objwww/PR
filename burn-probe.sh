#!/bin/sh
INC='sum(increase(rpc_server_call_duration_seconds_count{service_name="checkout",rpc_response_status_code!="OK"}[2m]))'
TOT='sum(increase(rpc_server_call_duration_seconds_count{service_name="checkout"}[2m]))'
snap() { curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode "query=$INC" | head -c 160; echo; }
tothi() { curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode "query=$TOT" | head -c 160; echo; }

echo "baseline err_2m:"; snap
echo "baseline tot_2m:"; tothi

echo '== shape1: 缺 address =='
docker run --rm --network opentelemetry-demo curlimages/curl:8.8.0 -s -m 8 -o /dev/null -w 'http=%{http_code}\n' \
  -X POST http://frontend:8080/api/checkout -H 'Content-Type: application/json' \
  -d '{"userId":"burn-shape1","email":"burn@example.com","userCurrency":"USD","creditCard":{"creditCardNumber":"4432-8015-6152-0454","creditCardExpirationMonth":1,"creditCardExpirationYear":2039,"creditCardCvv":123}}'
sleep 15
echo 'after shape1 err_2m:'; snap

echo '== shape2: 仅 userId =='
docker run --rm --network opentelemetry-demo curlimages/curl:8.8.0 -s -m 8 -o /dev/null -w 'http=%{http_code}\n' \
  -X POST http://frontend:8080/api/checkout -H 'Content-Type: application/json' \
  -d '{"userId":"burn-shape2"}'
sleep 15
echo 'after shape2 err_2m:'; snap

echo '== shape3: 加购后非法 currency 结账 =='
docker run --rm --network opentelemetry-demo curlimages/curl:8.8.0 sh -c '
  curl -s -o /dev/null -X POST http://frontend:8080/api/cart -H "Content-Type: application/json" -d "{\"item\":{\"productId\":\"LS4PSXUnUM5\",\"quantity\":1},\"userId\":\"burn-shape3\"}";
  curl -s -m 8 -o /dev/null -w "checkout_http=%{http_code}\n" -X POST http://frontend:8080/api/checkout -H "Content-Type: application/json" -d "{\"userId\":\"burn-shape3\",\"email\":\"burn@example.com\",\"address\":{\"streetAddress\":\"a\",\"zipCode\":\"1\",\"city\":\"c\",\"state\":\"S\",\"country\":\"C\"},\"userCurrency\":\"XXX\",\"creditCard\":{\"creditCardNumber\":\"4432-8015-6152-0454\",\"creditCardExpirationMonth\":1,\"creditCardExpirationYear\":2039,\"creditCardCvv\":123}}"'
sleep 15
echo 'after shape3 err_2m:'; snap
echo 'tot_2m:'; tothi
