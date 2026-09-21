#!/bin/sh
RID=47648add-c984-469b-91b9-ede903a10838
NEWTOKEN() {
  curl -s -b /tmp/p311.cookie -c /tmp/p311.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
  grep XSRF-TOKEN /tmp/p311.cookie | tail -1 | awk '{print $NF}'
}
T=$(NEWTOKEN)
echo "=== 打标实验=有效（token_len=${#T}） ==="
curl -s -b /tmp/p311.cookie -X POST "http://127.0.0.1:8080/api/eval/governance/runs/$RID/governance-tag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"tag":"VALID"}'; echo
echo '=== 读回 run-tags ==='
curl -s -b /tmp/p311.cookie "http://127.0.0.1:8080/api/eval/governance/run-tags"; echo
