curl -s http://127.0.0.1:9090/api/v1/rules | python3 -c "
import json,sys
d = json.load(sys.stdin)
for g in d['data']['groups']:
    for r in g['rules']:
        if r['name'] in ('ArenaFulfillmentGap','ArenaOrderZeroFlow'):
            print(g['name'], r['name'], 'health='+r['health'], 'state='+r.get('state',''))
            print('  expr:', ' '.join(r['query'].split()))
            print('  duration:', r.get('duration'), 'lastError:', r.get('lastError',''))
" 2>/dev/null || curl -s http://127.0.0.1:9090/api/v1/rules | head -c 400
