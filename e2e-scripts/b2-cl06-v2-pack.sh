#!/bin/sh
# b2-cl06-v2-pack.sh —— CL-06 v2 窗口证据打包（含 BA-141 报告原文取证）
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
PK=/tmp/b2cl06v2-pack
rm -rf "$PK"; mkdir -p "$PK"
echo "== BA-141：异常 run 报告原文（raw_text 前 400 字） =="
G "select left(regexp_replace(raw_text,'[\" \\t\\n]+',' ','g'),400) from rca_report where run_id='3564fee0-236f-4fb8-87c7-b17a197f86b4'" > "$PK/ba141-report-raw.txt"
cat "$PK/ba141-report-raw.txt"
echo "== BA-141：checkpoint final_missing_information =="
G "select left(regexp_replace(final_missing_information::text,'[\" \\t\\n]+',' ','g'),300) from rca_primary_checkpoint where run_id='3564fee0-236f-4fb8-87c7-b17a197f86b4'" > "$PK/ba141-final-missing.txt"
cat "$PK/ba141-final-missing.txt"
echo "== BA-141：历史同型面全量（PRIMARY DEAD attempts=0） =="
G "select t.run_id||'|'||ru.state||'|'||ru.created_at from rca_task t join rca_run ru on ru.id=t.run_id where t.task_key='PRIMARY_INVESTIGATE' and t.state='DEAD' and t.attempt_count=0 order by ru.created_at" > "$PK/ba141-historical-cases.txt"
wc -l "$PK/ba141-historical-cases.txt"
echo "== v2 全 run 的 verify2 汇总 =="
for d in /opt/build/runs-b2cl06v2/*/; do
  rid=$(cut -d= -f2 "$d/run-id.txt" 2>/dev/null)
  v2=$(cat "$d/phase10-verify2.exit" 2>/dev/null || echo none)
  echo "$(basename $d)|run=$rid|verify2=$v2"
done > "$PK/v2-runs-summary.txt"
cat "$PK/v2-runs-summary.txt"
echo "== 打包 =="
cp -a /opt/build/pr-logs/b2cl06-v2 "$PK/logs-batches"
cp -a /opt/build/runs-b2cl06v2 "$PK/runs"
cp -a /tmp/v2probe "$PK/manual-verify2-probe" 2>/dev/null || true
cp /opt/build/b2-cl06-override-v5.yml /opt/build/b2-cl06-retry2.sh /opt/build/b2-cl06-v2-relaunch.sh /opt/build/b2-cl06-v2-snap.sh /opt/build/b2-cl06-v2-restore.sh "$PK/" 2>/dev/null
cp /opt/build/b2tree/e2e-b2-cl06-v2.sh /opt/build/b2tree/b2-cl06-verify2.py /opt/build/b2tree/b2-verify2-selftest.py "$PK/" 2>/dev/null
tar -C /tmp -czf /tmp/b2cl06v2-pack.tgz "$(basename $PK)"
du -sh /tmp/b2cl06v2-pack.tgz
find "$PK" -type f | wc -l
