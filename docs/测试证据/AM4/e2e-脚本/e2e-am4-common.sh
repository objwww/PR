#!/bin/sh
# ============================================================================
# e2e-am4-common.sh —— AM4 E2E 公共函数（只读查询、记账、批次 ID、证据清单）
#
# 纪律（AM4 落码方案附录 §一，v1.4）：
#   - 脚本不含凭据、固定生产 ID、"失败后换 mock"分支；
#   - 连接信息从部署环境变量取得，绝不 dump；
#   - 所有 DB 访问只读（psql 单条 SELECT），断言不直写业务事实表。
#
# 证据面（runs/<UTC批次>/）：sql/ 与 raw/ 目录、commands.log（每条 SQL 与每场景
# 调用逐条记账）、assertions.tsv（断言流水，runall 汇总为 assertions.json）、
# suite-manifest.json / sha256sums.txt 由 runall 批次收尾统一生成。
#
# 用法：各场景脚本 source 本文件后调用 e4_begin / e4_assert_eq / e4_summary。
# 环境变量：AM4_PG_CONTAINER（必填，195 部署侧 PG 容器名）、
#           AM4_PG_USER（缺省 control_app）、AM4_PG_DB（缺省 control）；
#           E4_BATCH_ID / E4_RUN_DIR（runall 统一批次时导出，单场景运行则自建）。
# ============================================================================

E4_BATCH_ID="${E4_BATCH_ID:-am4-$(date -u +%Y%m%dT%H%M%SZ)}"
E4_RUN_DIR="${E4_RUN_DIR:-docs/测试证据/AM4/runs/$E4_BATCH_ID}"
E4_PASS_COUNT=0
E4_FAIL_COUNT=0

# 批次/场景事件记账（commands.log 缺失可写时静默跳过——证据面不得反向阻断测试）
e4_note() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" \
        >> "$E4_RUN_DIR/commands.log" 2>/dev/null || :
}

# 只读 SQL：经部署侧 psql 容器执行单条 SELECT（连接取环境变量，不落凭据）；
# 每条 SQL 逐条记入 commands.log（附录 §一 sql/ 前后只读事实对拍的执行留痕）
e4_sql() {
    e4_note "SQL: $1"
    docker exec "${AM4_PG_CONTAINER:?需要 AM4_PG_CONTAINER（195 部署侧 PG 容器名）}" \
        psql -U "${AM4_PG_USER:-control_app}" -d "${AM4_PG_DB:-control}" \
        -Atqc "$1"
}

# 计数断言：e4_assert_eq <描述> <实际> <期望>（流水同步落 assertions.tsv）
e4_assert_eq() {
    desc="$1"; actual="$2"; expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "  PASS: $desc (=$actual)"
        E4_PASS_COUNT=$((E4_PASS_COUNT + 1))
        printf '%s|%s|%s|true\n' "$desc" "$actual" "$expected" \
            >> "$E4_RUN_DIR/assertions.tsv" 2>/dev/null || :
    else
        echo "  FAIL: $desc (actual=$actual expected=$expected)"
        E4_FAIL_COUNT=$((E4_FAIL_COUNT + 1))
        printf '%s|%s|%s|false\n' "$desc" "$actual" "$expected" \
            >> "$E4_RUN_DIR/assertions.tsv" 2>/dev/null || :
    fi
}

# 批次/场景开始：建证据目录（sql/ 只读事实对拍、raw/ 脱敏组件输出）+ 记账
e4_begin() {
    mkdir -p "$E4_RUN_DIR/sql" "$E4_RUN_DIR/raw"
    e4_note "BEGIN batch=$E4_BATCH_ID scenario=${0##*/} git=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "[AM4-E2E] batch=$E4_BATCH_ID dir=$E4_RUN_DIR"
}

# 断言流水（TSV）→ 机器可读 JSON 数组（runall 批次收尾调用；描述中的引号转义）
e4_assertions_json() {
    {
        printf '[\n'
        first=1
        while IFS='|' read -r desc actual expected pass; do
            [ -n "$desc" ] || continue
            esc=$(printf '%s' "$desc" | sed 's/\\/\\\\/g; s/"/\\"/g')
            [ "$first" -eq 1 ] || printf ',\n'
            first=0
            printf '{"desc":"%s","actual":"%s","expected":"%s","pass":%s}' \
                "$esc" "$actual" "$expected" "$pass"
        done < "$E4_RUN_DIR/assertions.tsv" 2>/dev/null
        printf '\n]\n'
    } > "$E4_RUN_DIR/assertions.json"
}

# 批次收尾：assertions.json 落盘；任一 FAIL 即非零退出（缺场景=FAIL，同 runall 纪律）
e4_summary() {
    e4_note "SUMMARY scenario=${0##*/} pass=$E4_PASS_COUNT fail=$E4_FAIL_COUNT"
    e4_assertions_json
    if [ "$E4_FAIL_COUNT" -gt 0 ]; then
        echo "[AM4-E2E] FAIL: pass=$E4_PASS_COUNT fail=$E4_FAIL_COUNT"
        exit 1
    fi
    echo "[AM4-E2E] PASS: 全部断言通过 (pass=$E4_PASS_COUNT)"
}
