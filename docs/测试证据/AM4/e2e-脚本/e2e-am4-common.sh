#!/bin/sh
# ============================================================================
# e2e-am4-common.sh —— AM4 E2E 公共函数（只读查询、记账、批次 ID、证据清单）
#
# 纪律（AM4 落码方案附录 §一）：
#   - 脚本不含凭据、固定生产 ID、"失败后换 mock"分支；
#   - 连接信息从部署环境变量取得，绝不 dump；
#   - 所有 DB 访问只读（psql 单条 SELECT），断言不直写业务事实表。
#
# 用法：各场景脚本 source 本文件后调用 e4_begin / e4_assert_eq / e4_summary。
# 环境变量：AM4_PG_CONTAINER（必填，195 部署侧 PG 容器名）、
#           AM4_PG_USER（缺省 control_app）、AM4_PG_DB（缺省 control）。
# ============================================================================

E4_BATCH_ID="${E4_BATCH_ID:-am4-$(date -u +%Y%m%dT%H%M%SZ)}"
E4_RUN_DIR="${E4_RUN_DIR:-docs/测试证据/AM4/runs/$E4_BATCH_ID}"
E4_PASS_COUNT=0
E4_FAIL_COUNT=0

# 只读 SQL：经部署侧 psql 容器执行单条 SELECT（连接取环境变量，不落凭据）
e4_sql() {
    docker exec "${AM4_PG_CONTAINER:?需要 AM4_PG_CONTAINER（195 部署侧 PG 容器名）}" \
        psql -U "${AM4_PG_USER:-control_app}" -d "${AM4_PG_DB:-control}" \
        -Atqc "$1"
}

# 计数断言：e4_assert_eq <描述> <实际> <期望>
e4_assert_eq() {
    desc="$1"; actual="$2"; expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "  PASS: $desc (=$actual)"
        E4_PASS_COUNT=$((E4_PASS_COUNT + 1))
    else
        echo "  FAIL: $desc (actual=$actual expected=$expected)"
        E4_FAIL_COUNT=$((E4_FAIL_COUNT + 1))
    fi
}

# 批次开始：建证据目录 + 归档拓扑事实（只采集，不 dump 配置与凭据）
e4_begin() {
    mkdir -p "$E4_RUN_DIR"
    {
        echo "batch=$E4_BATCH_ID"
        echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "pg_container=${AM4_PG_CONTAINER:-unset}"
    } > "$E4_RUN_DIR/suite-manifest.txt"
    echo "[AM4-E2E] batch=$E4_BATCH_ID dir=$E4_RUN_DIR"
}

# 批次收尾：任一 FAIL 即非零退出（缺场景=FAIL，同 runall 纪律）
e4_summary() {
    echo "$E4_PASS_COUNT $E4_FAIL_COUNT" > "$E4_RUN_DIR/assertions.txt"
    if [ "$E4_FAIL_COUNT" -gt 0 ]; then
        echo "[AM4-E2E] FAIL: pass=$E4_PASS_COUNT fail=$E4_FAIL_COUNT"
        exit 1
    fi
    echo "[AM4-E2E] PASS: 全部断言通过 (pass=$E4_PASS_COUNT)"
}
