#!/bin/sh
# 操作员 env（600 权限；值源自 deploy/.env，绝不回显）——R7 真窗第三批（MC34/RG01/O01）
export R7_CONTROL_URL="http://127.0.0.1:8080"
export R7_WEBHOOK_BEARER="$(grep -E '^CONTROL_WEBHOOK_BEARER_TOKEN=' /opt/build/pr/deploy/.env | head -1 | cut -d= -f2-)"
export R7_RELEASE_BEARER="$(grep -E '^APP_RELEASE_API_BEARER=' /opt/build/pr/deploy/.env | head -1 | cut -d= -f2-)"
export R7_PSQL_CMD="docker exec deploy-postgres-1 psql"
export R7_PG_URL="postgresql://postgres:$(grep -E '^POSTGRES_PASSWORD=' /opt/build/pr/deploy/.env | head -1 | cut -d= -f2-)@localhost:5432/pr_agent"
export R7_RUNS_DIR="/opt/build/runs-r7batch3"
export R7_PRIMARY_ALLOWLIST="prometheus.query,logs.query,change.query,prometheus.instant,prometheus.catalog,prometheus.label_values,prometheus.rules,logs.aggregate,change.diff,alert.history,runbook.catalog,runbook.fetch,rca_history.search,code.search,code.read"
