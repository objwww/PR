#!/bin/sh
echo '--- worker 容器 ---'
docker ps -a --format '{{.Names}} | {{.Status}}' | grep eval || echo none
echo '--- 失败分析 ---'
grep -A 15 'APPLICATION FAILED\|FAILURE ANALYSIS\|LoggingFailureAnalysisReporter' /tmp/p2-worker.log | head -30
echo '--- 启动完成行 ---'
grep -c 'Started ControlApplication' /tmp/p2-worker.log
