#!/bin/sh
# RR01 验收执行：emit → collect → compare，产物落 /opt/build/or01/
mkdir -p /opt/build/or01
cd /opt/build/pr/deploy
sh audit-runtime.sh --project-dir . --emit-expect --out /opt/build/or01/test-manifest.json >/dev/null
sh audit-runtime.sh --project-dir . --out /opt/build/or01/runtime-manifest.json >/dev/null
sh audit-runtime.sh --project-dir . --expect /opt/build/or01/test-manifest.json \
    --out /opt/build/or01/runtime-manifest.json > /opt/build/or01/verdict.json
RC=$?; echo "compare_exit=$RC (0=MATCH)"
echo '=== verdict ==='
python3 - <<'PY'
import json
d = json.load(open('/opt/build/or01/verdict.json'))
print('overall =', d['overall'])
for v in d['verdicts']:
    print(f"{v['key']:24s} {v['verdict']:8s} | {v['detail'][:140]}")
PY
echo '=== runtime-manifest 摘要 ==='
python3 - <<'PY'
import json
m = json.load(open('/opt/build/or01/runtime-manifest.json'))
print('meta:', m['meta'])
print('containers:', len(m['containers']))
for c in m['containers']:
    print(' ', c['name'], c['state'], c['image_ref'][:40], 'mem=', c['mem_limit'])
print('app_build:', m['app_build']['jar_md5'], 'commit=', m['app_build']['git_commit'])
print('flyway rows:', len(m['flyway']['rows']), 'query_ok=', m['flyway']['query_ok'])
print('release_assets:', m['release_assets'])
print('skill_binding_rows:', m['skill_binding_rows'])
print('config:', {k: (v[:40] if isinstance(v, str) else v) for k, v in m['config'].items()})
PY
