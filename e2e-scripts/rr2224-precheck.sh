#!/bin/sh
# v 发射前预检 v2：锚定 pgrep 前缀（执行器 cmdline 恒为 `sh /opt/build/rr2224-run.sh` 开头，
# 调用方 wrapper 以 bash -c 开头不会误匹配）+ prometheus-am0 容器侧可达性 + mock 存活
pgrep -f '^sh /opt/build/rr2224-run\.sh' && { echo STILL-RUNNING; exit 1; } || echo NO-EXECUTOR
docker exec rriso-control-app-1 bash -c 'timeout 3 bash -c "cat < /dev/null > /dev/tcp/prometheus-am0/9090" 2>/dev/null && echo PROM-REACHABLE || echo PROM-UNREACHABLE'
ss -ltn | grep -E '18100|18443' || echo MOCK-DOWN
