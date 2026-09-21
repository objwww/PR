#!/bin/sh
# 烧损源探测：arena 前端端口/网络、load-generator 压力、现网错误率基线
echo '== frontend 容器端口与网络 =='
docker inspect frontend --format '{{range $k,$v := .NetworkSettings.Networks}}net={{$k}} {{end}}{{.Config.Image}}' 2>/dev/null
docker port frontend 2>/dev/null || echo '(无宿主端口映射——走容器网络)'
echo '== load-generator 现状 =='
docker inspect load-generator --format '{{range $k,$v := .NetworkSettings.Networks}}net={{$k}} {{end}}'
docker logs load-generator --since 5m 2>&1 | tail -3
echo '== checkout gRPC 近 5m 总量/错误量基线 =='
curl -s 'http://127.0.0.1:9090/api/v1/query?query=sum(rate(rpc_server_call_duration_seconds_count{service_name="checkout"}[5m]))' | grep -o '"value":\[[^]]*\]'
curl -s 'http://127.0.0.1:9090/api/v1/query?query=sum(rate(rpc_server_call_duration_seconds_count{service_name="checkout",rpc_response_status_code!="OK"}[5m]))' | grep -o '"value":\[[^]]*\]'
echo '== 容器网络内试发一单缺字段订单（探测 gRPC 是否非 OK） =='
docker run --rm --network $(docker inspect frontend --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}') curlimages/curl:8.8.0 -s -m 5 -o /tmp/ord.out -w 'http=%{http_code}\n' \
  -X POST http://frontend:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"userId":"burn-probe","itemId":"LS4PSXUnUM5","quantity":1}'
head -c 200 /tmp/ord.out 2>/dev/null; echo
sleep 8
echo '== 试发后 checkout 非 OK 计数（应出现 burn-probe 带来的增量） =='
curl -s 'http://127.0.0.1:9090/api/v1/query?query=sum(rate(rpc_server_call_duration_seconds_count{service_name="checkout",rpc_response_status_code!="OK"}[5m]))' | grep -o '"value":\[[^]]*\]'
