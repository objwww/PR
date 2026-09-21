#!/bin/sh
echo '=== compose control-app build 配置 ==='
cd /opt/build/pr/deploy
docker compose config 2>/dev/null | grep -A 12 '^  control-app:' | head -20
echo '=== Dockerfile ==='
cat /opt/build/pr/control-app/Dockerfile 2>/dev/null | head -20
echo '=== 镜像构建时间 vs worker 所用镜像 ==='
docker images --format '{{.ID}} {{.CreatedAt}} {{.Repository}}:{{.Tag}}' | grep control-app | head -5
docker inspect eval-worker-std --format 'worker-image={{.Image}} started={{.State.StartedAt}}'
echo '=== 镜像内 SingleCaseScorer.class 是否含新回退 SQL ==='
docker run --rm --entrypoint sh pr-agent/control-app:0.0.1-SNAPSHOT -c \
  "unzip -p /app/app.jar BOOT-INF/classes/com/objwww/pr/control/eval/application/SingleCaseScorer.class | grep -c 'rca_tool_invocation' || echo MISSING"
echo '=== 宿主 target jar 同查 ==='
cd /opt/build/pr
ls -la control-app/target/*.jar 2>/dev/null
unzip -p control-app/target/*.jar BOOT-INF/classes/com/objwww/pr/control/eval/application/SingleCaseScorer.class 2>/dev/null | grep -c 'rca_tool_invocation'
