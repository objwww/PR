#!/bin/sh
# 故障传导诊断：payment 失败面 + checkout 流量面 + flagd 现值
echo '--- payment 近 5m 行数与级别分布 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%7D&limit=5&since=5m' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
for s in d['data']['result'][:3]:
    print('level=',s['stream'].get('detected_level'),'lines=',len(s['values']))
    for v in s['values'][:2]: print('  sample:',v[1][:120])
"
echo '--- payment 近 5m error 行 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%2C%20detected_level%3D%22error%22%7D&limit=2&since=5m' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
n=sum(len(s['values']) for s in d['data']['result'])
print('error_lines=',n)
for s in d['data']['result'][:1]:
    for v in s['values'][:2]: print('  sample:',v[1][:160])
"
echo '--- checkout 近 10m 行数 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D&limit=500&since=10m' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('streams=',len(d['data']['result']),'lines<=',sum(len(s['values']) for s in d['data']['result']))
"
echo '--- flagd 现值核验 ---'
docker exec flagd-admin-am3 python3 -c "import json;d=json.load(open('/flags/demo.flagd.json'));print('paymentFailure defaultVariant =',d['flags']['paymentFailure']['defaultVariant'])"
echo '--- load-generator 近 5m 行数 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22load-generator%22%7D&limit=5&since=5m' 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('streams=',len(d['data']['result']))
for s in d['data']['result'][:2]:
    print('  level=',s['stream'].get('detected_level'),'lines=',len(s['values']))
    for v in s['values'][:1]: print('  sample:',v[1][:100])
" 2>/dev/null
