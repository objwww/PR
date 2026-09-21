#!/bin/sh
# RR02（OR-01）：漂移可见性——期望面注入一个运行库没有的迁移版本
# （等价于"测试环境某工具 schema 换版"的镜像情形：测试 manifest 与运行面版本不一致）
# 期望：flyway DRIFT 可见、overall=DRIFT、退出码 1（旧资格被阻断——对拍器出口纪律）
set -u
OUT=/tmp/rr02
rm -rf "$OUT"; mkdir -p "$OUT"

cp /opt/build/or01/test-manifest.json "$OUT/test-manifest-injected.json"
python3 - <<'PY'
import json
p = "/tmp/rr02/test-manifest-injected.json"
d = json.load(open(p))
migs = d.get("migrations_expected", [])
d["migrations_expected"] = sorted(set(migs + ["V999__rr02_drift_inject.sql"]))
json.dump(d, open(p, "w"), ensure_ascii=False, indent=2)
print("注入 V999 后期望迁移数:", len(d["migrations_expected"]))
PY

sh /opt/build/audit-runtime.sh --project-dir /opt/build/pr/deploy \
    --out "$OUT/runtime-manifest.json" --expect "$OUT/test-manifest-injected.json" \
    > "$OUT/verdict.json" 2>/dev/null
RC=$?
echo "verdict_rc=$RC（期望 1=DRIFT 阻断）"
python3 - <<'PY'
import json
d = json.load(open("/tmp/rr02/verdict.json"))
print("overall =", d["overall"], "(期望 DRIFT)")
for v in d["verdicts"]:
    print(f"  {v['key']:24s} {v['verdict']:8s} | {v['detail'][:140]}")
assert d["overall"] == "DRIFT", "FAIL: 漂移不可见"
assert any(v["key"] == "flyway" and v["verdict"] == "DRIFT" for v in d["verdicts"])
print("ASSERT-DRIFT-VISIBLE: PASS")
PY

echo "== 附：真实运行面当前对拍（无注入，应回到无 DRIFT 基线） =="
sh /opt/build/audit-runtime.sh --project-dir /opt/build/pr/deploy \
    --out "$OUT/runtime-manifest-baseline.json" --expect /opt/build/or01/test-manifest.json \
    > "$OUT/verdict-baseline.json" 2>/dev/null
RCB=$?
echo "baseline_rc=$RCB"
python3 -c "
import json
d=json.load(open('/tmp/rr02/verdict-baseline.json'))
print('baseline overall =',d['overall'])
for v in d['verdicts']: print(f\"  {v['key']:24s} {v['verdict']:8s} | {v['detail'][:120]}\")"
echo "RR02-DONE"
