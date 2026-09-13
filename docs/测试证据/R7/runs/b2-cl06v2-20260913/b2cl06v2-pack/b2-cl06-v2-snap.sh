#!/bin/sh
# b2-cl06-v2-snap.sh <label> —— 当前窗口日志快照到分目录（防同名覆盖）
LBL="${1:-unlabeled}"
DEST="/opt/build/pr-logs/b2cl06-v2/$LBL"
mkdir -p "$DEST"
cp -a /opt/build/pr-logs/b2-cl06-v2-t1.log /opt/build/pr-logs/b2-cl06-v2-t2.log \
      /opt/build/pr-logs/b2-cl06-v2-t3.log /opt/build/pr-logs/b2-cl06-v2-t4.log \
      /opt/build/pr-logs/b2-cl06-v2-t5.log /opt/build/pr-logs/b2-cl06-v2-t6.log \
      /opt/build/pr-logs/b2-cl06-v2-retry.log /opt/build/pr-logs/b2-cl06-v2-aggregate.log \
      "$DEST/" 2>/dev/null
echo "== snapshot $LBL =="
ls -1 "$DEST"
echo "== retry tail =="
tail -10 /opt/build/pr-logs/b2-cl06-v2-retry.log
echo "== aggregate =="
cat /opt/build/pr-logs/b2-cl06-v2-aggregate.log
echo "== runs dirs =="
ls -1d /opt/build/runs-b2cl06v2/*/
