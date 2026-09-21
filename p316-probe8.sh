#!/bin/sh
set -e
echo '=== 运行容器内 class 常量串（::long vs ::bigint） ==='
docker exec deploy-control-app-1 sh -c "unzip -p /app/app.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class 2>/dev/null | strings | grep -o '::long\|::bigint' | sort | uniq -c" || echo '(路径不对，试查 jar 位置)'
docker exec deploy-control-app-1 sh -c "ls /app 2>/dev/null; ls / | head -20"
echo '=== 镜像创建时间 ==='
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT --format '{{.Created}}'
echo '=== 构建树源文件里 ::long 是否还在 ==='
grep -c '::long' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java || echo '0 (源码已是 bigint)'
grep -c '::bigint' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.java
echo '=== target 里编译产物的 class 串 ==='
cd /opt/build/pr
unzip -p control-app/target/control-app-0.0.1-SNAPSHOT.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/persistence/PostgresAgentOpsReader.class 2>/dev/null | strings | grep -o '::long\|::bigint' | sort | uniq -c || echo '(target jar 读取失败)'
