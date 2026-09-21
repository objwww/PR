#!/bin/sh
# RR02 追查：V108 漂移定性——当前构建树重新 emit 期望面，与运行库对拍
set -u
OUT=/tmp/rr02
sh /opt/build/audit-runtime.sh --project-dir /opt/build/pr/deploy \
    --emit-expect --out "$OUT/test-manifest-fresh.json" >/dev/null
python3 - <<'PY'
import json
d = json.load(open("/tmp/rr02/test-manifest-fresh.json"))
migs = d["migrations_expected"]
print("当前构建树期望迁移数:", len(migs))
print("尾部 6 个:", [m.split("__")[0] for m in migs[-6:]])
print("git_commit:", d["build"]["git_commit"][:12], "dirty:", d["build"]["worktree_dirty"])
PY
sh /opt/build/audit-runtime.sh --project-dir /opt/build/pr/deploy \
    --out "$OUT/runtime-manifest-fresh-run.json" --expect "$OUT/test-manifest-fresh.json" \
    > "$OUT/verdict-fresh.json" 2>/dev/null
RC=$?
echo "fresh_verdict_rc=$RC"
python3 -c "
import json
d=json.load(open('/tmp/rr02/verdict-fresh.json'))
print('fresh overall =',d['overall'])
for v in d['verdicts']: print(f\"  {v['key']:24s} {v['verdict']:8s} | {v['detail'][:130]}\")"
echo "== 库内 V108 行 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -A -t -F'|' -c \
  "SELECT version, description, success, installed_on::time(0) FROM flyway_schema_history WHERE version IN ('108','107','106') ORDER BY version DESC"
echo "== 构建树 V10x 文件 =="
ls /opt/build/pr/control-app/src/main/resources/db/migration/ | grep -E "^V(99|10[0-9])" || echo "(无 V99/V10x 文件)"
echo "RR02-FRESH-DONE"
