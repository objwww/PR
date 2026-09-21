#!/bin/sh
echo '=== SecurityConfig 类中的 api/ 路径常量 ==='
docker exec deploy-control-app-1 sh -c 'unzip -p /app.jar BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class' | strings | grep -E '^/api|api/' | head -30
echo '=== DiagSessionController 类中的映射常量 ==='
docker exec deploy-control-app-1 sh -c 'unzip -p /app.jar BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/DiagSessionController.class' | strings | grep -iE 'diag|free|question' | head -12
