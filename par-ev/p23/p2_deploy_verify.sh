#!/bin/sh
# P2: rules 文件落位 -> 容器内 promtool 校验 -> md5 对拍 -> HUP 热加载 -> /api/v1/rules 在场实证
# 守则：promtool 失败则中止，绝不 HUP
OUT=/tmp/p2_verify.txt
: > "$OUT"
{
echo "===== [D0] strip CR if any ====="
sed -i 's/\r$//' /opt/build/pr/deploy/alert/prometheus/rules/memory-gate.yml
echo
echo "===== [D1] host file landed ====="
ls -la /opt/build/pr/deploy/alert/prometheus/rules/
md5sum /opt/build/pr/deploy/alert/prometheus/rules/memory-gate.yml
echo
echo "===== [D2] docker cp + promtool check (inside container) ====="
docker cp /opt/build/pr/deploy/alert/prometheus/rules/memory-gate.yml prometheus-am0:/tmp/memory-gate.yml
docker exec prometheus-am0 promtool check rules /tmp/memory-gate.yml > /tmp/p2_promtool.txt 2>&1
RC=$?
cat /tmp/p2_promtool.txt
echo "promtool_exit=$RC"
if [ "$RC" -ne 0 ]; then echo "PROMTOOL_FAILED_ABORT_NO_HUP"; exit 1; fi
echo
echo "===== [D3] md5 compare: host file vs container view (exec cat | md5sum) ====="
echo -n "host:     "; md5sum /opt/build/pr/deploy/alert/prometheus/rules/memory-gate.yml
echo -n "in-cntnr: "; docker exec prometheus-am0 sh -c 'cat /etc/prometheus/rules/memory-gate.yml | md5sum'
echo
echo "===== [D4] HUP hot reload ====="
date '+before HUP: %F %T'
docker kill --signal=HUP prometheus-am0
sleep 12
date '+after  HUP: %F %T'
echo
echo "===== [D5] /api/v1/rules full dump ====="
curl -s 'http://127.0.0.1:9090/api/v1/rules' > /tmp/p2_api_rules.json
wc -c /tmp/p2_api_rules.json
echo
echo "===== [D6] presence check: three rule names in dump ====="
grep -c 'MemoryGateTier1' /tmp/p2_api_rules.json
grep -c 'MemoryGateTier2' /tmp/p2_api_rules.json
grep -c 'MemoryGateTier3' /tmp/p2_api_rules.json
echo
echo "===== [D7] python3 parsed summary (if available) ====="
if command -v python3 >/dev/null 2>&1; then
python3 - <<'PYEOF'
import json
d = json.load(open('/tmp/p2_api_rules.json'))
for g in d['data']['groups']:
    if g['name'] == 'memory-gate':
        print('group:', g['name'], 'file:', g['file'], 'interval:', g.get('interval'), 'eval total:', g.get('evaluationTime'))
        for r in g['rules']:
            print(' rule:', r['name'], '| health:', r['health'], '| lastError:', repr(r.get('lastError','')), '| state:', r.get('state'), '| duration:', r.get('duration'), '| labels:', r.get('labels'))
PYEOF
else
echo "python3 not available on host"
fi
} >> "$OUT" 2>&1
echo "VERIFY_DONE"
