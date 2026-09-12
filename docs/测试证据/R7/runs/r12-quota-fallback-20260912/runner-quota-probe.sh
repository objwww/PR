#!/bin/sh
# R12 R4 真机正证 runner：额度耗尽自动切换探针（非破坏性），操作员 env 从 deploy/.env
# 运行时读取（不落证据、不回显）；本次窗姿态 = 主路由 qwen3-max-preview@预算钥匙
# （已耗尽→litellm 429 budget_exceeded）+ 备路由同模型@master（异 quota_scope，
# A4 矩阵可切；同模型备账号=额度耗尽的真实运维语义，且链路行为与已验证 qwen 路径一致）。
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
export R7_PRIMARY_MODEL="qwen3-max-preview"
export R7_FALLBACK_MODEL="qwen3-max-preview"
mkdir -p "$R7_RUNS_DIR"
cd /opt/build/pr/docs/测试证据/R7/e2e-脚本
exec sh ./e2e-r12-quota-fallback.sh
