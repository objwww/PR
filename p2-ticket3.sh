#!/bin/sh
echo '--- ticket 告警定义（line 211 起）---'
docker exec prometheus-am0 sh -c "sed -n '211,240p' /etc/prometheus/rules/prometheus-rules-checkout.yml"
echo '--- 当前 rate2h/rate6h 比值 ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=slo:sli_error:ratio_rate2h{sloth_service=%22checkout%22}'" | tr '\n' ' ' | grep -o '"value":\[[0-9.,"]*\]' || echo '(空)'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=slo:sli_error:ratio_rate6h{sloth_service=%22checkout%22}'" | tr '\n' ' ' | grep -o '"value":\[[0-9.,"]*\]' || echo '(空)'
echo
echo '--- 当前错误是否已停（近5分钟 checkout 非 OK 计数）---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=increase%28rpc_server_call_duration_seconds_count%7Bservice_name%3D%22checkout%22%2Crpc_response_status_code%21%3D%22OK%22%7D%5B5m%5D%29'" | tr '\n' ' ' | grep -o '"value":\[[0-9.,"]*\]' || echo '(空=近5分钟零错误)'
