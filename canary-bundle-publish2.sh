#!/bin/sh
# B4 评窗运行脸 v2：DB 只读取内容→注入 canary 段→API 发布+激活→等 worker 拍→查判定
SCRIPT_DIR="/opt/build/pr/docs/测试证据/R7/e2e-脚本"
. "${SCRIPT_DIR}/e2e-r7-common.sh"
. /opt/build/r7-operator-env.sh
RUNS="/opt/build/runs-r7batch3/canary-runtime"
mkdir -p "$RUNS"

# 1. 激活 bundle digest（API）
_code="$(r7_http GET /api/config-bundles/active R7_RELEASE_BEARER "" "$RUNS/active.resp")"
_r7_digest="$(sed -E 's/.*"bundleDigest":"([0-9a-f]{64})".*/\1/' "$RUNS/active.resp")"
echo "active-digest=$_r7_digest"

# 2. DB 只读取出 content（jsonb→json 文本）
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select content::text from config_bundle where bundle_digest='$_r7_digest' limit 1;" > "$RUNS/bundle-content.json"
wc -c "$RUNS/bundle-content.json"

# 3. 注入 canary 段
python3 - "$RUNS" <<'EOF'
import json, sys
d = sys.argv[1]
content = json.load(open(d + "/bundle-content.json"))
content["canary"] = {"window": {"min_samples": 2, "consecutive_windows": 2,
                                "window_minutes": 60,
                                "max_absolute_fail_rate": 0.5,
                                "relative_tolerance": 0.2}}
json.dump(content, open(d + "/canary-bundle.json", "w"), ensure_ascii=False)
print("canary-section-injected")
EOF
[ $? = 0 ] || r7_fail "canary 注入失败"

# 4. 发布+激活+资格桥
NEW_DIGEST="$(r7_publish_bundle "$RUNS/canary-bundle.json" canary)"
echo "new-digest=$NEW_DIGEST"
r7_activate "$NEW_DIGEST" "$RUNS"
echo "activated"
r7_qualify "$NEW_DIGEST" "$RUNS" || true

# 5. 等 worker 拍评窗
sleep 45
echo '=== canary_window_verdict ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select window_seq, verdict, eligible_incidents, coalesce(raw_counts->>'excluded_reason','-') from canary_window_verdict order by id desc limit 5;"
echo CANARY-RUNTIME-DONE
exit 0
