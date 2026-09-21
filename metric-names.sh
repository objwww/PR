#!/bin/sh
curl -sG 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query={service="checkout"}' 2>/dev/null | python3 -c "
import sys, json
d = json.load(sys.stdin)
names = {}
for r in d['data']['result']:
    names.setdefault(r['metric'].get('__name__', ''), []).append(r['metric'])
for n in sorted(names):
    labels = names[n][0]
    dims = [k for k in labels.keys() if k not in ('__name__', 'job', 'instance', 'service')]
    print(n, 'series=', len(names[n]), 'dims=', dims[:8])
"
