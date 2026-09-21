#!/bin/sh
echo '== SLI 实时值（5m/30m/1h/2h） =='
for w in 5m 30m 1h 2h 6h; do
  v=$(curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode "query=slo:sli_error:ratio_rate${w}{sloth_id=\"checkout-availability\"}" | head -c 200)
  echo "$w -> $v"
done
echo '== page 告警表达式直查（应返回 series 才会 firing） =='
Q='max without (sloth_window) (slo:sli_error:ratio_rate5m{sloth_id="checkout-availability", sloth_service="checkout", sloth_slo="availability"} > (14.4 * 0.01)) and max without (sloth_window) (slo:sli_error:ratio_rate1h{sloth_id="checkout-availability", sloth_service="checkout", sloth_slo="availability"} > (14.4 * 0.01))'
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode "query=$Q" | head -c 300
echo
echo '== alertmanager 路由 =='
docker exec alertmanager-am0 sh -c 'cat /etc/alertmanager/alertmanager.yml 2>/dev/null || cat /alertmanager/alertmanager.yml 2>/dev/null' | head -60
