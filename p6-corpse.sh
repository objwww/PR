#!/bin/sh
cd /opt/build/pr
sed 's/--rm //; s/eval-worker-p6real/eval-worker-p6dbg/' /tmp/p6w.sh > /tmp/p6wdbg.sh
# 日志改道文件，容器失败后仍可 inspect-env
sed -i 's|> /tmp/p6-worker.log 2>&1 &|> /tmp/p6-worker.log 2>\&1 \&\& true|' /tmp/p6wdbg.sh
sh /tmp/p6wdbg.sh || true
sleep 6
docker inspect eval-worker-p6dbg --format '{{.State.Status}}' 2>&1
docker inspect eval-worker-p6dbg --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^SPRING_APPLICATION_JSON=' | cut -c1-160
docker inspect eval-worker-p6dbg --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -cE '^SPRING_APPLICATION_JSON='
docker rm -f eval-worker-p6dbg >/dev/null 2>&1
