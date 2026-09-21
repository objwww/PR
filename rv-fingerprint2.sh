#!/bin/sh
# RV 部署验收收尾指纹（无 set -e；正确类名；live 双容器）
JH=/opt/jdk-21.0.12.1+1/bin
echo '=== 镜像换代记录（应含今日新 .Created） ==='
docker inspect -f '{{.Id}} {{.Created}}' pr-agent/control-app:0.0.1-SNAPSHOT
docker inspect -f '{{.Id}} {{.Created}}' pr-agent/notify-app:0.0.1-SNAPSHOT
echo '=== control live jar 剩余指纹 ==='
rm -rf /tmp/fpL && mkdir -p /tmp/fpL && cd /tmp/fpL
docker cp deploy-control-app-1:/app/app.jar /tmp/live-ctl.jar
"$JH/jar" -xf /tmp/live-ctl.jar BOOT-INF/classes
CL=/tmp/fpL/BOOT-INF/classes
"$JH/javap" -p -constants -cp "$CL" 'com.objwww.pr.control.alert.application.tool.InFlightToolCancels$Handle$Phase' | grep -o CANCELLED_BEFORE_START
"$JH/javap" -cp "$CL" com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository | grep -o insertIfAbsent
"$JH/javap" -cp "$CL" 'com.objwww.pr.control.alert.application.agent.RoleRunner$RoleDriveResult$ChildResult' | grep -o ChildResult
"$JH/javap" -cp "$CL" com.objwww.pr.control.alert.application.tool.ToolGateway | grep -c InFlightToolCancels
"$JH/javap" -cp "$CL" com.objwww.pr.control.infrastructure.nativeexec.NativeInvestigationExecutor | grep -o finalizeWithReceipt
"$JH/javap" -c -cp "$CL" com.objwww.pr.control.alert.application.agent.ContextAssembler | grep -c TRUSTED_EXCLUSION_MARK
echo '=== notify live jar 指纹 ==='
rm -rf /tmp/fpN && mkdir -p /tmp/fpN && cd /tmp/fpN
docker cp deploy-notify-app-1:/app/app.jar /tmp/live-ntf.jar
"$JH/jar" -xf /tmp/live-ntf.jar BOOT-INF/classes
NL=/tmp/fpN/BOOT-INF/classes
"$JH/javap" -p -constants -cp "$NL" com.objwww.pr.notify.domain.service.FencedNotifyExecutor | grep -o MAX_DETAIL_CHARS
"$JH/javap" -c -cp "$NL" com.objwww.pr.notify.domain.service.FencedNotifyExecutor | grep -c channel_not_configured
echo '=== 健康终拍 ==='
curl -s -o /dev/null -w 'control8080=%{http_code}\n' http://127.0.0.1:8080/actuator/health
curl -s -o /dev/null -w 'web8090=%{http_code}\n' http://127.0.0.1:8090/
docker logs deploy-control-app-1 --since 10m 2>&1 | grep -cE ' ERROR ' || true
docker ps --format '{{.Names}} {{.Status}}' | grep -E 'control-app|notify|web'
echo FINGERPRINT2-DONE
