#!/bin/sh
# checkout/frontend（可读域）错误类指标现值——paymentFailure=100% 传导面
echo '--- checkout 标签的错误类指标（现行值）---'
curl -sG 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query={service="checkout"}' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = d['data']['result']
print('total series:', len(rows))
for r in rows:
    name = r['metric'].get('__name__', '')
    if any(k in name.lower() for k in ['error', 'fail']):
        print(' ', name, '=', r['value'][1], {k: v for k, v in r['metric'].items() if k not in ('__name__', 'job', 'instance')})
"
echo '--- frontend 标签的错误类指标 ---'
curl -sG 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query={service="frontend"}' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = d['data']['result']
print('total series:', len(rows))
for r in rows:
    name = r['metric'].get('__name__', '')
    if any(k in name.lower() for k in ['error', 'fail']):
        print(' ', name, '=', r['value'][1])
"
