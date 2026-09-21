#!/bin/sh
echo '--- ticket 变体表达式窗口 ---'
docker exec prometheus-am0 sh -c "grep -A 20 'severity: ticket' /etc/prometheus/rules/prometheus-rules-checkout.yml | grep -E 'rate[0-9]+[mh]' | head -6"
echo '--- 当前各窗错误比 ---'
for W in rate1h rate6h rate3d; do
  V=$(docker exec prometheus-am0 sh -c "wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=slo:sli_error:ratio_${W}%7Bsloth_id%3D%22checkout-availability%22%7D'" | tr '{' '\n' | grep -o '\"\[1[0-9]*\]\":\"[0-9.e-]*' | head -2)
  echo "$W => $V"
done
