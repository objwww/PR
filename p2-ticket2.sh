#!/bin/sh
echo '--- rules 文件中第二个 checkout 告警（ticket）的完整定义 ---'
docker exec prometheus-am0 sh -c "grep -n 'alert: checkout' /etc/prometheus/rules/prometheus-rules-checkout.yml"
docker exec prometheus-am0 sh -c "sed -n '30,80p' /etc/prometheus/rules/prometheus-rules-checkout.yml"
echo '--- 当前 slo 窗口比值 ---'
docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=slo%3Asli_error%3Aratio_rate1h%7Bsloth_service%3D%22checkout%22%7D'" | tr ',' '\n' | grep -o '"value":\[.*\]' | head -3
