#!/bin/sh
# B4 评窗运行脸：取激活 bundle 内容→注入 canary 段→发布+激活→等 worker 拍→查判定
SCRIPT_DIR="/opt/build/pr/docs/测试证据/R7/e2e-脚本"
. "${SCRIPT_DIR}/e2e-r7-common.sh"
. /opt/build/r7-operator-env.sh
RUNS="/opt/build/runs-r7batch3/canary-runtime"
mkdir -p "$RUNS"

# 1. 当前激活内容
_code="$(r7_http GET /api/config-bundles/active R7_RELEASE_BEARER "" "$RUNS/active.resp")"
[ "$_code" = "200" ] || r7_fail "active HTTP $_code"
_r7_digest="$(sed -E 's/.*"bundleDigest":"([0-9a-f]{64})".*/\1/' "$RUNS/active.resp")"
echo "active-digest=$_r7_digest"

# 2. 取该 bundle 内容（GET /api/config-bundles/<digest>）
_code="$(r7_http GET "/api/config-bundles/$_r7_digest" R7_RELEASE_BEARER "" "$RUNS/bundle.resp")"
echo "get-bundle=$_code"

# 3. 用 python 给 content 注入 canary 段并重发布
python3 - "$RUNS" <<'EOF'
import json, sys
d = sys.argv[1]
row = json.load(open(d + "/bundle.resp"))
content = row.get("content") or row.get("bundle", {}).get("content")
assert content is not None, "no content in bundle.resp: " + row.keys().__str__()
content["canary"] = {"window": {"min_samples": 2, "consecutive_windows": 2,
                                "window_minutes": 60,
                                "max_absolute_fail_rate": 0.5,
                                "relative_tolerance": 0.2}}
json.dump(content, open(d + "/canary-bundle.json", "w"), ensure_ascii=False)
print("canary-section-injected")
EOF
[ $? = 0 ] || r7_fail "canary 注入失败"
NEW_DIGEST="$(r7_publish_bundle "$RUNS/canary-bundle.json" canary)"
echo "new-digest=$NEW_DIGEST"
r7_activate "$NEW_DIGEST" "$RUNS"
echo "activated"
r7_qualify "$NEW_DIGEST" "$RUNS" || true

# 4. 等 worker 拍评窗（poll 2s；等 40s）
sleep 40
echo '=== canary_window_verdict ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select window_seq, verdict, eligible_incidents, coalesce(raw_counts->>'excluded_reason','-') from canary_window_verdict order by id desc limit 5;"
echo CANARY-RUNTIME-DONE
exit 0
