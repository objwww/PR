#!/bin/sh
echo '--- 并行会话今日产物（应都在）:'
ls -la /tmp/live-worker.jar /tmp/live-ba191.jar /tmp/ssc.jar /tmp/Gen.java 2>&1
echo '--- hold 文件（应都在）:'
find /tmp /opt/build/pr -maxdepth 1 -name '*hold*' 2>/dev/null
echo '--- eval-worker-std 存活:'
docker ps --format '{{.Names}} {{.Status}}' | grep eval-worker
echo '--- 关键容器快检:'
docker ps --format '{{.Names}}' | grep -cE 'deploy-control|deploy-postgres|litellm'
echo '--- 最终磁盘:'
df -h / | tail -1
