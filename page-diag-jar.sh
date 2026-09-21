#!/bin/sh
# PAGE 批部署面诊断：host jar 为何缺新类、镜像怎么构建的
cd /opt/build/pr
echo '== 源文件在场？ =='
ls -la control-app/src/main/java/com/objwww/pr/control/eval/application/EvalLaunchGate.java 2>&1
echo '== 编译产物在场？ =='
find control-app/target/classes -name 'EvalLaunchGate*' 2>/dev/null || echo 'target/classes 无该类'
echo '== host jar 时间与内容面 =='
ls -la control-app/target/*.jar
JAR=control-app/target/control-app-0.0.1-SNAPSHOT.jar
unzip -l "$JAR" 2>/dev/null | grep -c 'eval/application/' || true
unzip -l "$JAR" 2>/dev/null | grep -E 'DelegationReceipt|EvalLaunchExecutor' | sed 's/^/  rv-era: /' || echo '  rv-era 类也不在（jar 很旧）'
echo '== Dockerfile =='
cat control-app/Dockerfile 2>/dev/null || cat Dockerfile 2>/dev/null
echo '== 镜像构建时间 =='
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT --format '{{.Created}}' 2>/dev/null
echo '== 容器内 jar 里有 rv-era 类吗 =='
docker exec deploy-control-app-1 sh -c "cd /tmp && rm -rf pgchk2 && mkdir pgchk2 && cd pgchk2 && (unzip -o -q /app/app.jar 'BOOT-INF/classes/com/objwww/pr/control/eval/application/*' 2>/dev/null && ls BOOT-INF/classes/com/objwww/pr/control/eval/application/ | wc -l) || echo unzip不可用"
