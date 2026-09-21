#!/bin/sh
export PATH=/opt/jdk-21.0.12.1+1/bin:$PATH
cd /tmp && rm -rf _jarx && mkdir _jarx && cd _jarx
JAR=$(ls /opt/build/pr/control-app/target/*.jar | head -1)
echo "jar=$JAR"
jar xf "$JAR" BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/DiagSessionController.class
echo '=== SecurityConfig api/ 常量 ==='
strings BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class | grep -E 'api/' | head -30
echo '=== SecurityConfig 其他可疑常量（free/diag） ==='
strings BOOT-INF/classes/com/objwww/pr/control/infrastructure/config/SecurityConfig.class | grep -iE 'free|diag' | head -6
echo '=== DiagSessionController 映射常量 ==='
strings BOOT-INF/classes/com/objwww/pr/control/alert/interfaces/DiagSessionController.class | grep -iE 'free|question|diag' | head -10
