#!/bin/sh
# ============================================================================
# e2e-am4-runall.sh —— AM4 E2E 总 runner（落码方案附录 §一/§三，v1.4）
#
# 顺序执行 E2E-M4-00~11 全部 12 个场景（§三 执行顺序：正常基线 00 → 真实业务链
# 01~05/07/09/10 → 确定性与恢复 06/08/11），任一失败即非零退出；缺失或 SKIP 的
# 场景一律总体失败——"无 skip"硬纪律（§四 G2 一票否决项）。
#
# 统一批次产证（runs/<UTC批次>/）：suite-manifest.json（批次 ID、git commit、
# 镜像/model/provider/config/agent/tool registry digest——取自部署环境变量，未
# 设置项如实标注 unset，绝不 dump 凭据）、assertions.json（全场景断言流水）、
# commands.log（每场景调用与每条只读 SQL 留痕）、sql/、raw/、sha256sums.txt
# （附录 §三.7：先脱敏后封存）。
#
# fixtures 对拍（附录 §一/§三.7）：docs 证据面副本 vs classpath
# （control-app/src/main/resources/am4/fixtures/）sha256 逐文件对拍，不一致即
# 总体失败。
#
# 环境变量：AM4_PG_CONTAINER（必填）、AM4_PG_USER/AM4_PG_DB（见 common）、
#   AM4_CONTROL_IMAGE（control-app 镜像名，设了才采集 RepoDigest）、
#   AM4_MODEL_ID / AM4_PROVIDER / AM4_CONFIG_DIGEST / AM4_AGENT_REGISTRY_DIGEST /
#   AM4_TOOL_REGISTRY_DIGEST（环境 digest 留痕，未设=unset）。
# ============================================================================

set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"

# ---- 统一批次：12 场景共享一个 UTC 批次目录 -------------------------------
E4_BATCH_ID="am4-$(date -u +%Y%m%dT%H%M%SZ)"
E4_RUN_DIR="docs/测试证据/AM4/runs/$E4_BATCH_ID"
export E4_BATCH_ID E4_RUN_DIR
FAILED=0
RESULTS="$E4_RUN_DIR/scenario-results.tsv"
mkdir -p "$E4_RUN_DIR/sql" "$E4_RUN_DIR/raw"
: > "$RESULTS"

e4_note() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" \
        >> "$E4_RUN_DIR/commands.log" 2>/dev/null || :
}

run_scenario() {
    script="$1"
    if [ -f "$HERE/$script" ]; then
        echo "==== [AM4-E2E] $script ===="
        e4_note "RUN $script"
        if sh "$HERE/$script"; then
            e4_note "PASS $script"
            printf '%s|PASS\n' "$script" >> "$RESULTS"
        else
            echo "==== [AM4-E2E] $script FAIL ===="
            e4_note "FAIL $script"
            printf '%s|FAIL\n' "$script" >> "$RESULTS"
            FAILED=1
        fi
    else
        echo "==== [AM4-E2E] $script 缺失（无 skip 纪律 → 总体失败）===="
        e4_note "MISSING $script"
        printf '%s|MISSING\n' "$script" >> "$RESULTS"
        FAILED=1
    fi
}

# ---- 环境预检（§三.1）：fixtures sha256 对拍 + 环境 digest 留痕 ------------
e4_note "PREFLIGHT begin batch=$E4_BATCH_ID"
FIX_EVID="$REPO/docs/测试证据/AM4/fixtures"
FIX_CP="$REPO/control-app/src/main/resources/am4/fixtures"
FIX_MATCH="true"
for f in "logs/logs-query.json" "changes/change-query.json"; do
    h1=$(sha256sum "$FIX_EVID/$f" 2>/dev/null | cut -d' ' -f1)
    h2=$(sha256sum "$FIX_CP/$(basename "$f")" 2>/dev/null | cut -d' ' -f1)
    if [ -n "$h1" ] && [ "$h1" = "$h2" ]; then
        echo "[AM4-E2E] fixtures 对拍一致: $f sha256=$h1"
    else
        echo "[AM4-E2E] fixtures 对拍不一致: $f（evidence=${h1:-缺失} classpath=${h2:-缺失}）"
        FIX_MATCH="false"
        FAILED=1
    fi
done

IMAGE_DIGEST="unset"
if [ -n "${AM4_CONTROL_IMAGE:-}" ]; then
    IMAGE_DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' \
        "$AM4_CONTROL_IMAGE" 2>/dev/null || echo "$AM4_CONTROL_IMAGE")
fi

# ---- §三 执行顺序：基线 → 真实业务链 → 确定性与恢复 -----------------------
run_scenario "e2e-m4-00-normal.sh"
run_scenario "e2e-m4-01-f1.sh"
run_scenario "e2e-m4-02-f2.sh"
run_scenario "e2e-m4-03-f3.sh"
run_scenario "e2e-m4-04-f4.sh"
run_scenario "e2e-m4-05-generation.sh"
run_scenario "e2e-m4-07-injection.sh"
run_scenario "e2e-m4-09-shadow.sh"
run_scenario "e2e-m4-10-budget.sh"
run_scenario "e2e-m4-06-fault-drill.sh"
run_scenario "e2e-m4-08-replay.sh"
run_scenario "e2e-m4-11-source-outage.sh"

# ---- 批次收尾：12 场景结果核验 + 产证封存（§三.7）-------------------------
MISSING=0
for s in 00-normal 01-f1 02-f2 03-f3 04-f4 05-generation 06-fault-drill \
         07-injection 08-replay 09-shadow 10-budget 11-source-outage; do
    grep -q "^e2e-m4-$s.sh|PASS$" "$RESULTS" || MISSING=1
done
if [ "$MISSING" -ne 0 ]; then
    echo "[AM4-E2E] 存在未 PASS/缺失场景（无 skip 纪律 → 总体失败）"
    FAILED=1
fi

{
    printf '{\n'
    printf '  "batch_id": "%s",\n' "$E4_BATCH_ID"
    printf '  "generated_at": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "git_commit": "%s",\n' "$(git rev-parse HEAD 2>/dev/null || echo unknown)"
    printf '  "control_image_digest": "%s",\n' "$IMAGE_DIGEST"
    printf '  "model_id": "%s",\n' "${AM4_MODEL_ID:-unset}"
    printf '  "provider": "%s",\n' "${AM4_PROVIDER:-unset}"
    printf '  "config_digest": "%s",\n' "${AM4_CONFIG_DIGEST:-unset}"
    printf '  "agent_registry_digest": "%s",\n' "${AM4_AGENT_REGISTRY_DIGEST:-unset}"
    printf '  "tool_registry_digest": "%s",\n' "${AM4_TOOL_REGISTRY_DIGEST:-unset}"
    printf '  "fixtures_sha256_match": %s,\n' "$FIX_MATCH"
    printf '  "scenarios": [\n'
    first=1
    while IFS='|' read -r script result; do
        [ "$first" -eq 1 ] || printf ',\n'
        first=0
        printf '    {"script": "%s", "result": "%s"}' "$script" "$result"
    done < "$RESULTS"
    printf '\n  ]\n}\n'
} > "$E4_RUN_DIR/suite-manifest.json"

# 断言流水 → 机器可读 assertions.json（复用 common 生成函数）
. "$HERE/e2e-am4-common.sh"
e4_assertions_json

# 证据封存：先脱敏（脚本全链只读、无凭据落盘）后 sha256（清单自身除外）
e4_note "SEALED sha256sums.txt"
( cd "$E4_RUN_DIR" && find . -type f ! -name sha256sums.txt \
    -exec sha256sum {} \; | sort > sha256sums.txt )

if [ "$FAILED" -ne 0 ]; then
    echo "[AM4-E2E] runall FAIL（批次=$E4_BATCH_ID 产证=$E4_RUN_DIR；存在失败/缺失场景或 fixtures 不一致）"
    exit 1
fi
echo "[AM4-E2E] runall PASS（00~11 全 12 场景执行且通过；批次=$E4_BATCH_ID 产证=$E4_RUN_DIR）"
