#!/bin/sh
# 拉 locust 并发到 20（paymentFailure=100% 已开）→ 查 checkout/frontend 错误面
docker exec load-generator python3 -c "
import urllib.request
req = urllib.request.Request('http://127.0.0.1:8089/swarm',
    data=b'user_count=20&spawn_rate=5',
    headers={'Content-Type': 'application/x-www-form-urlencoded'})
print('swarm:', urllib.request.urlopen(req).read().decode()[:120])
"
echo "等 100s 让流量与故障传导 ..."
sleep 100
echo '--- checkout 近 2m 级别分布 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D&limit=1000&since=2m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
lv = {}
for s in d['data']['result']:
    lv[s['stream'].get('detected_level')] = lv.get(s['stream'].get('detected_level'), 0) + len(s['values'])
print('checkout 2m levels:', lv)
"
echo '--- checkout 近 2m failed/error 内容行 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D%20%7C~%20%22(?i)fail%7Cerror%22&limit=3&since=2m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
print('lines=', sum(len(s['values']) for s in r))
for s in r[:2]:
    for v in s['values'][:2]:
        print(' lvl=', s['stream'].get('detected_level'), v[1][:150])
"
echo '--- locust checkout 失败率 ---'
docker exec load-generator python3 -c "
import urllib.request, json
d = json.loads(urllib.request.urlopen('http://127.0.0.1:8089/stats/requests').read())
print('user_count=', d.get('user_count'))
for r in d.get('stats', []):
    if 'checkout' in r['name']:
        print(r['method'], r['name'], 'reqs=', r['num_requests'], 'fails=', r['num_failures'])
"
