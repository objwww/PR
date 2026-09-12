#!/bin/sh
# R12 修补批真窗 runner：A0 供应商回执链套件（非破坏性），操作员 env 从 deploy/.env
# 运行时读取（不落证据、不回显）；本次窗姿态 = AGENT_MODEL=fallback-probe-nonexistent
# + AGENT_MODEL_FALLBACK=qwen3-max-preview（额度耗尽自动切换实测，§18 真机面）。
set -e
get() { grep "^$1=" /opt/build/pr/deploy/.env | head -1 | cut -d= -f2-; }
POSTGRES_PASSWORD="$(get POSTGRES_PASSWORD)"
WEBHOOK="$(get ALERTMANAGER_WEBHOOK_BEARER_TOKEN)"
RELEASE="$(get APP_RELEASE_API_BEARER)"
export R7_CONTROL_URL="http://127.0.0.1:8080"
export R7_WEBHOOK_BEARER="$WEBHOOK"
export R7_RELEASE_BEARER="$RELEASE"
export R7_PSQL_CMD="docker exec deploy-postgres-1 psql"
export R7_PG_URL="postgresql://postgres:${POSTGRES_PASSWORD}@127.0.0.1:5432/pr_agent"
export R7_RUNS_DIR="/opt/build/pr/r12-runs"
mkdir -p "$R7_RUNS_DIR"
cd /opt/build/pr/docs/测试证据/R7/e2e-脚本
exec sh ./e2e-r7-a0-provider-receipt-chain.sh
