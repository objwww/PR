#!/bin/sh
# 故障可见面验证（基线流量 3 用户 + paymentFailure=100%）：
# checkout/frontend（模型可读域）是否有失败可证据化行；payment 仅诊断对照
sleep 120
echo '--- checkout 近 3m 行率/级别 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D&limit=1000&since=3m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
lv = {}
for s in r:
    lv[s['stream'].get('detected_level')] = lv.get(s['stream'].get('detected_level'), 0) + len(s['values'])
print('levels:', lv)
"
echo '--- checkout 近 3m fail/error 内容行 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22checkout%22%7D%20%7C~%20%22(?i)fail%7Cerror%22&limit=5&since=3m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
print('lines=', sum(len(s['values']) for s in r))
for s in r[:2]:
    for v in s['values'][:3]:
        print(' lvl=', s['stream'].get('detected_level'), v[1][:150])
"
echo '--- frontend 近 3m fail/error 内容行 ---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22frontend%22%7D%20%7C~%20%22(?i)fail%7Cerror%22&limit=5&since=3m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
print('lines=', sum(len(s['values']) for s in r))
for s in r[:2]:
    for v in s['values'][:3]:
        print(' lvl=', s['stream'].get('detected_level'), v[1][:150])
"
echo '--- payment 近 3m（诊断对照，模型不可读）---'
docker exec deploy-loki-1 wget -qO- 'http://127.0.0.1:3100/loki/api/v1/query_range?query=%7Bservice_name%3D%22payment%22%7D&limit=1000&since=3m' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
lv = {}
for s in r:
    lv[s['stream'].get('detected_level')] = lv.get(s['stream'].get('detected_level'), 0) + len(s['values'])
print('levels:', lv)
"
