#!/bin/bash
# M-d 真值验证：有六要素行的 run 的 run 级出数
set -u
KEY=$(docker exec deploy-control-app-1 sh -c 'env | grep -E "^APP_OPERATOR_API_BEARER=" | head -1 | cut -d= -f2-')
for RID in eccb56f2-7959-4ab4-9009-3dbcf4e3609d 59a5e7a4-ca05-4cda-92d6-79acaa35c116; do
  echo "run=$RID six-parts: $(curl -s -H "Authorization: Bearer $KEY" \
    "http://127.0.0.1:8080/api/eval/runs/$RID/six-parts" | head -c 240)"
done
echo "judge(v2): $(curl -s -H "Authorization: Bearer $KEY" \
  "http://127.0.0.1:8080/api/eval/runs/59a5e7a4-ca05-4cda-92d6-79acaa35c116/judge" | head -c 240)"
