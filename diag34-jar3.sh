#!/bin/sh
echo '=== SecurityConfig 类中的 api/ 路径常量 ==='
docker exec deploy-control-app-1 sh -c 'cd /tmp && /opt/jdk-21.0.12.1+1/bin/jar xf /app.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class 2>/dev/null; ls -la /tmp/BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class' | tail -1
docker cp deploy-control-app-1:/tmp/BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class /tmp/_secfg.class
strings /tmp/_secfg.class | grep -E 'api/' | head -30
echo '=== DiagSessionController 映射常量 ==='
docker exec deploy-control-app-1 sh -c 'cd /tmp && /opt/jdk-21.0.12.1+1/bin/jar xf /app.jar BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/DiagSessionController.class 2>/dev/null'
docker cp deploy-control-app-1:/tmp/BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/DiagSessionController.class /tmp/_diagc.class
strings /tmp/_diagc.class | grep -iE 'diag|free|question' | head -12
