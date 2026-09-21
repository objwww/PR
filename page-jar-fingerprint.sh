#!/bin/sh
# PAGE 批 live jar 指纹 v2：镜像 COPY 的是 -exec 胖 jar（Dockerfile 注释明确）
set -e
EXECJAR=/opt/build/pr/control-app/target/control-app-0.0.1-SNAPSHOT-exec.jar
CONT=$(docker exec deploy-control-app-1 sh -c 'sha256sum /app/app.jar' | cut -d' ' -f1)
HOST=$(sha256sum "$EXECJAR" | cut -d' ' -f1)
echo "live-jar = $CONT"
echo "exec-jar = $HOST"
echo '--- exec jar 内 PAGE 批新类 ---'
unzip -l "$EXECJAR" | grep -E 'EvalLaunchGate|launch' | sed 's/^/  /' || echo '  未找到 EvalLaunchGate!!'
if [ "$CONT" = "$HOST" ]; then
  echo 'LIVE-JAR-MATCH=OK（live jar = 本批构建产物）'
else
  echo 'LIVE-JAR-MATCH=MISMATCH'
  exit 1
fi
