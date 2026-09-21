#!/bin/sh
# FO06 真机 200 面：既有 eval_run 的质量汇总读面
OB=$(docker exec deploy-control-app-1 env | grep '^APP_OPERATOR_API_BEARER=' | cut -d= -f2-)
curl -s -o /tmp/q200.out -w 'quality-200=%{http_code}\n' \
  -H "Authorization: Bearer $OB" \
  http://127.0.0.1:8080/api/eval/runs/439f2cb2-5755-4068-8519-3324e44c2914/quality
cat /tmp/q200.out; echo
