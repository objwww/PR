#!/bin/sh
# rr1415-pack.sh —— RR14/15 证据打包（秘密三文件绝不入包；键只留 alias+sha12）
set -e
P=/opt/build/pr-logs/rr1415-pack
rm -rf "$P"; mkdir -p "$P/logs" "$P/probes" "$P/scripts" "$P/runs"
cp /opt/build/pr-logs/rr1415-main.log "$P/" 2>/dev/null || true
cp -r /opt/build/pr-logs/rr1415/. "$P/logs/" 2>/dev/null || true
rm -f "$P/logs/rr15b-app.log.tmp" 2>/dev/null || true

# 探针定性证据落档（配额面/键面/模型面——均为轻量探针）
sh /opt/build/rr-model-probe.sh > "$P/probes/model-quota-probe.txt" 2>&1 || true
sh /opt/build/rr-key-validate.sh > "$P/probes/virtual-key-validate.txt" 2>&1 || true
cp /tmp/rr-f2.out "$P/probes/forensic2-r15b-trace.txt" 2>/dev/null || true
cp /tmp/rr-f3.out "$P/probes/forensic3-403-classify.txt" 2>/dev/null || true

# 终局 DB 定性：RR14 末轮两 run 的调用账本聚合 + 明细（脱敏：不取 prompt/请求体）
Q() { docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -c "$1"; }
{
  echo "== RR14 各 run 模型调用聚合（按时间倒序前 6）=="
  Q "select run_id||' rows='||count(*)||' succ='||count(*) filter (where state='SUCCESS')||' authden='||count(*) filter (where state='FAILED' and error_code='AUTH_DENIED')||' budget='||count(*) filter (where state='FAILED' and error_code='OUTPUT_BUDGET_EXHAUSTED') from rca_model_call group by run_id order by max(created_at) desc limit 6"
  echo "== 末轮 run 明细 =="
  for r in $(Q "select distinct run_id from rca_model_call order by run_id desc limit 2"); do
    echo "-- run $r"
    Q "select state||'|'||coalesce(error_code,'-') from rca_model_call where run_id='$r' order by created_at"
  done
  echo "== 末轮 run 终态 =="
  Q "select id||' '||state from rca_run order by created_at desc limit 4"
} > "$P/probes/db-audit-classify.txt" 2>&1 || true

# 脚本快照（本面全部产物）
for s in rr1415-run.sh rr1415-inject.sh rr1415-restart2.sh rr-iso-gen-env.sh rr-lock-probe.sh rr-port-pick.sh rr-model-probe.sh rr-key-validate.sh rr15b-forensic.sh rr-forensic2.sh rr-forensic3.sh rr-forensic4.sh; do
  cp "/opt/build/$s" "$P/scripts/" 2>/dev/null || echo "缺 $s" >> "$P/scripts/MISSING.txt"
done
cp /opt/build/pr/rr-iso/docker-compose.iso.yml "$P/scripts/" 2>/dev/null || true

# inject 车产物（runs 目录：bundle/run-id 等；无秘密）
cp -r /opt/build/runs-rriso/. "$P/runs/" 2>/dev/null || echo "runs-rriso 空" > "$P/runs/EMPTY.txt"
find "$P/runs" -type f -name "*.env" -delete 2>/dev/null || true

# 密钥扫描（键形态/秘密文件名/密码字面量三面）
echo "== 密钥扫描 =="
grep -rInE 'sk-[A-Za-z0-9_-]{16,}|AGENT_MODEL_API_KEY=sk-|POSTGRES_PASSWORD=[a-f0-9]{20,}' "$P" | head -5 || true
ls "$P" | grep -E 'rr-iso\.(env|secrets|posture)' && echo "发现秘密文件（BAD）" || echo "秘密三文件不在包（OK）"
echo "== 打包 =="
cd /opt/build/pr-logs && tar czf rr1415-evidence.tgz rr1415-pack
sha256sum rr1415-evidence.tgz
du -sh rr1415-evidence.tgz
find rr1415-pack -type f | wc -l
