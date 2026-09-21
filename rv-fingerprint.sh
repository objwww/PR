#!/bin/sh
# 指纹与构建时间戳一锤定音：host jar vs 运行容器 jar
JH=/opt/jdk-21.0.12.1+1/bin
HJAR=/opt/build/pr/control-app/target/control-app-0.0.1-SNAPSHOT-exec.jar
LJAR=/tmp/live-app.jar
echo '=== host jar 构建时间戳（pom.properties） ==='
rm -rf /tmp/pp && mkdir -p /tmp/pp && cd /tmp/pp
"$JH/jar" -xf "$HJAR" META-INF/maven/com.objwww.pr/control-app/pom.properties
grep -E 'build|version' /tmp/pp/META-INF/maven/com.objwww.pr/control-app/pom.properties
echo '=== host jar 新类指纹（重新解包，raw） ==='
rm -rf /tmp/fp2 && mkdir -p /tmp/fp2 && cd /tmp/fp2
"$JH/jar" -xf "$HJAR" BOOT-INF/classes
CL=/tmp/fp2/BOOT-INF/classes
"$JH/javap" -p -constants -cp "$CL" 'com.objwww.pr.control.alert.application.tool.InFlightToolCancels$Handle$Phase' | grep -o CANCELLED_BEFORE_START
"$JH/javap" -cp "$CL" com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository | grep -o insertIfAbsent
"$JH/javap" -cp "$CL" 'com.objwww.pr.control.alert.application.agent.RoleDriveResult$ChildResult' | grep -o 'ChildResult'
echo '=== live jar == host jar? ==='
docker cp deploy-control-app-1:/app/app.jar /tmp/live-app2.jar
cmp -s /tmp/live-app2.jar "$HJAR" && echo LIVE-EQ-HOST || echo LIVE-NE-HOST
echo '=== live jar 时间戳 ==='
rm -rf /tmp/pp2 && mkdir -p /tmp/pp2 && cd /tmp/pp2
"$JH/jar" -xf /tmp/live-app2.jar META-INF/maven/com.objwww.pr/control-app/pom.properties
grep -E 'build' /tmp/pp2/META-INF/maven/com.objwww.pr/control-app/pom.properties
echo FINGERPRINT-DONE
