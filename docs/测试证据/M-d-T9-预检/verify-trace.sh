#!/bin/bash
# M-d T9 §二 补验：trace-details 单点（真 rca_run_id）
set -u
KEY=$(docker exec deploy-control-app-1 sh -c 'env | grep -E "^APP_OPERATOR_API_BEARER=" | head -1 | cut -d= -f2-')
curl -s -H "Authorization: Bearer $KEY" \
  "http://127.0.0.1:8080/api/rca-runs/3112cc75-72b7-473c-a7c0-dd3d99565620/trace-details" | head -c 600
echo
