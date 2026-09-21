#!/bin/sh
set -eu
A=/tmp/b2cl06-archive
RID=1a5175cb-2dc8-4f90-8288-40966b20311f
rm -rf $A; mkdir -p $A/runs $A/pr-logs $A/scripts $A/tmp

cp -a /opt/build/runs-b2cl06 $A/runs/
cp -a /opt/build/pr-logs/b2-cl06-a0.log /opt/build/pr-logs/b2-cl06-a0-r*.log \
      /opt/build/pr-logs/b2-cl06-a0-try*.log /opt/build/pr-logs/b2-cl06-retry*.log \
      /opt/build/pr-logs/b2cl06-v3 /opt/build/pr-logs/b2cl06-v4a /opt/build/pr-logs/b2cl06-v4b \
      /opt/build/pr-logs/b2-cl0406-it.log $A/pr-logs/
cp -a /opt/build/e2e-b2-cl06.sh /opt/build/e2e-r7-common.sh /opt/build/b2-cl06-override.yml \
      /opt/build/b2-cl06-retry.sh /opt/build/b2-cl06-verify.py /opt/build/b2-cl06-restore.sh \
      /opt/build/b2-v4-orch.sh /opt/build/b2-v4b-relaunch.sh /opt/build/b2-v4-recon.sh \
      /opt/build/b2-diag6.sh /opt/build/b2-diag7.sh $A/scripts/
cp -a /tmp/b2cl06/* $A/tmp/

echo "== PASS run 记忆链定格 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT checkpoint_revision, parent_memory_id IS NOT NULL, coalesce(left(parent_memory_id::text,8),'(无父=首行)'), left(memory_digest,12), schema_version
FROM rca_working_memory WHERE run_id='$RID' ORDER BY checkpoint_revision" > $A/memory-chain.txt
cat $A/memory-chain.txt
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -F '|' -c "
SELECT revision, phase, round_id, batches_used, steps_used, left(memory_id::text,8), (memory_digest IN (SELECT memory_digest FROM rca_working_memory WHERE run_id='$RID'))
FROM rca_primary_checkpoint WHERE run_id='$RID'" > $A/checkpoint-anchor.txt
cat $A/checkpoint-anchor.txt

echo "== 跨轮 prompt 可达性断言定格 =="
python3 /opt/build/b2-cl06-verify.py > $A/verify-output.txt 2>&1
tail -2 $A/verify-output.txt

echo "== 密钥终扫 面1：.env 实值定向 =="
LEAK=0
while IFS= read -r line; do
  k=$(printf '%s' "$line" | cut -d= -f1)
  v=$(printf '%s' "$line" | cut -d= -f2-)
  case "$v" in ""|true|false|*[!A-Za-z0-9_./:@~-]*) ;; esac
  [ -n "$v" ] || continue
  if grep -rqF -- "$v" $A 2>/dev/null; then echo "LEAK-VALUE key=$k"; LEAK=1; fi
done < /opt/build/pr/deploy/.env
[ "$LEAK" = "0" ] && echo "面1 无实值泄漏"

echo "== 密钥终扫 面2：词形 =="
grep -rEn 'sk-[A-Za-z0-9_-]{10,}|Bearer [A-Za-z0-9._-]{10,}|ghp_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{12,}' $A | head -10 || true
echo "面2 扫描完（上行无输出=零命中）"

tar -C /tmp -czf /tmp/b2cl06-evidence.tar.gz b2cl06-archive
md5sum /tmp/b2cl06-evidence.tar.gz
du -sh /tmp/b2cl06-evidence.tar.gz
echo PACK-DONE
