#!/bin/sh
echo '=== 运行 jar 里 SecurityConfig 的路径常量（找 diag/free 相关规则） ==='
docker exec deploy-control-app-1 sh -c "unzip -p /app.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class 2>/dev/null | strings | grep -E 'api/' | head -40"
echo '=== jar 里 DiagSessionController 的映射常量 ==='
docker exec deploy-control-app-1 sh -c "unzip -p /app.jar BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/DiagSessionController.class 2>/dev/null | strings | grep -E 'diag|free|questions' | head -10"
echo '=== app.jar 路径确认 ==='
docker exec deploy-control-app-1 sh -c "ls / | head; ls /app.jar 2>/dev/null || ls /app 2>/dev/null | head -3"
