#!/bin/sh
# RR03（OR-01）：采集权限缺失/实例不可达 → UNKNOWN 不当 MATCH、不泄密
# 手法：DOCKER_HOST 指向不可达端点（tcp://127.0.0.1:1）跑采集器——docker/exec/PG 面全失败，
#       全程零接触真实运行时。期望：overall=UNKNOWN（绝非 MATCH）、输出无任何秘密值。
# 采集器用独立副本 /opt/build/audit-runtime.sh（修复版），不动构建树。
set -u
OUT_DIR=/tmp/rr03
rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"

echo "== RR03 步骤1：不可达 DOCKER_HOST 纯采集 =="
DOCKER_HOST=tcp://127.0.0.1:1 sh /opt/build/audit-runtime.sh \
    --project-dir /opt/build/pr/deploy --out "$OUT_DIR/runtime-manifest-broken.json" \
    > "$OUT_DIR/collect-broken.stdout" 2>"$OUT_DIR/collect-broken.stderr"
RC1=$?
echo "collector_rc=$RC1（无 --expect=纯采集，恒 0；失败信息在 manifest 内）"
python3 - <<'PY'
import json
m = json.load(open("/tmp/rr03/runtime-manifest-broken.json"))
print("containers:", len(m["containers"]), "(期望 0=docker 面失败)")
print("flyway query_ok:", m["flyway"]["query_ok"], "rows:", len(m["flyway"]["rows"]), "(期望 False)")
print("jar_md5:", m["app_build"]["jar_md5"], "(期望 UNKNOWN)")
print("skill_binding_rows:", m["skill_binding_rows"], "(期望 UNKNOWN)")
print("config:", m["config"], "(期望白名单键全 ABSENT→由对拍判 UNKNOWN)")
PY

echo "== RR03 步骤2：同环境对拍（期望件=B0 真实件） =="
DOCKER_HOST=tcp://127.0.0.1:1 sh /opt/build/audit-runtime.sh \
    --project-dir /opt/build/pr/deploy --out "$OUT_DIR/runtime-manifest-broken2.json" \
    --expect /opt/build/or01/test-manifest.json \
    > "$OUT_DIR/verdict-broken.json" 2>/dev/null
RC2=$?
echo "verdict_rc=$RC2（非 MATCH=1）"
python3 - <<'PY'
import json
d = json.load(open("/tmp/rr03/verdict-broken.json"))
print("overall =", d["overall"], "(期望 UNKNOWN，绝不 MATCH)")
for v in d["verdicts"]:
    print(f"  {v['key']:24s} {v['verdict']:8s} | {v['detail'][:120]}")
assert d["overall"] != "MATCH", "FAIL: 采集失败冒充 MATCH"
print("ASSERT-OVERALL-NOT-MATCH: PASS")
PY

echo "== RR03 断言 B: 输出零秘密扫描 =="
if grep -E -i "(api[_-]?key|token|password|secret)[\"']?[:=][\"']?[A-Za-z0-9+/_-]{8,}" \
    "$OUT_DIR"/*.json "$OUT_DIR"/*.stdout 2>/dev/null; then
  echo "FAIL: 疑似秘密值出现"
else
  echo "PASS: 无秘密值出现"
fi
echo "RR03-DONE"
