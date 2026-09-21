#!/bin/sh
# B1-1 辅证：当前部署 jar 的 CL-03/流式修复标记（只读）
echo "== 提取 ContextAssembler.class 并扫 projectLogs（CL-03 投影）=="
docker exec deploy-control-app-1 sh -c "unzip -p /app/app.jar BOOT-INF/classes/com/objwww/pr/control/alert/application/agent/ContextAssembler.class" > /tmp/ca-deployed.class 2>/dev/null
ls -la /tmp/ca-deployed.class
grep -a -c "projectLogs" /tmp/ca-deployed.class || echo "projectLogs: 0（无 CL-03 投影）"
grep -a -c "appendTopLevel" /tmp/ca-deployed.class || echo "appendTopLevel: 0"
echo "== LogQueryExecutor 流式标记（RAW_READ_CAP_FACTOR/CountingInputStream）=="
docker exec deploy-control-app-1 sh -c "unzip -p /app/app.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/tool/LogQueryExecutor.class" > /tmp/lqe-deployed.class 2>/dev/null
ls -la /tmp/lqe-deployed.class
grep -a -c "CountingInputStream" /tmp/lqe-deployed.class || echo "CountingInputStream: 0"
echo "== RunReconciler（SR 批标记）=="
docker exec deploy-control-app-1 sh -c "unzip -l /app/app.jar" 2>/dev/null | grep -c "RunReconciler" || echo "RunReconciler: 0"
echo "== PrimaryCheckpointCommitService（CL-01 围栏标记）=="
docker exec deploy-control-app-1 sh -c "unzip -l /app/app.jar" 2>/dev/null | grep -c "PrimaryCheckpointCommitService" || echo "CL-01 commit service: 0"
