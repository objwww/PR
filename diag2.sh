#!/bin/sh
# locust 现状（限时限内层）+ checkout/payment 容器负载 + 5m 内 checkout 行率
timeout 25 docker exec load-generator python3 -c "
import urllib.request, json
d = json.loads(urllib.request.urlopen('http://127.0.0.1:8089/stats/requests', timeout=15).read())
print('user_count=', d.get('user_count'))
rows = [r for r in d.get('stats', []) if r['num_requests'] > 0][:8]
for r in rows:
    print(r['method'], r['name'][:30], 'reqs=', r['num_requests'], 'fails=', r['num_failures'])
" 2>&1
echo '--- 容器负载 ---'
docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' load-generator checkout payment 2>/dev/null
echo '--- checkout 近 5m 行率与样例 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D&limit=1000&since=5m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
n = sum(len(s['values']) for s in r)
print('lines_5m =', n)
for s in r[:1]:
    for v in s['values'][-3:]:
        print(' ', s['stream'].get('detected_level'), v[1][:120])
"
echo '--- payment 近 5m 行率 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%7D&limit=1000&since=5m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
n = sum(len(s['values']) for s in r)
print('lines_5m =', n)
"
