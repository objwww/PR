#!/bin/sh
# R4 遗留销账 runner：SPRING_APPLICATION_JSON 价目注入探针（非破坏性）。
# 操作员 env 从 deploy/.env 运行时读取（不落证据、不回显）。
# 前置（操作员临时姿态，跑完回收）：deploy/.env 注入
#   SPRING_APPLICATION_JSON={"app":{"model":{"price":{"qwen3-max-preview":
#     {"pricing-version":"op-inject-20260912","currency":"CNY",
#      "input-micros-per-1k":2000,"output-micros-per-1k":8000}}}}}
# 并 docker compose up -d control-app 使注入生效。
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
export R7_RUNS_DIR="/opt/build/pr/r4p-runs"
export R4_EXPECT_PV="op-inject-20260912"
export R4_EXPECT_CURRENCY="CNY"
mkdir -p "$R7_RUNS_DIR"
cd /opt/build/pr/docs/测试证据/R7/e2e-脚本
exec sh ./e2e-r4-pricing-inject.sh
