#!/bin/sh
# B2：CL-04/06 对位测试 195 树真跑（Testcontainers 隔离 PG，不触生产栈）
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp -pl control-app verify \
  -Dtest='R7ModelGatewayTest,R7RoleRunnerTest,ContextAssemblerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='PostgresWorkingMemoryIT' \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  > /opt/build/pr-logs/b2-cl0406-it.log 2>&1
grep -E 'Tests run:|BUILD' /opt/build/pr-logs/b2-cl0406-it.log | tail -10
echo "== 生产面健康复核（IT 隔离性）=="
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo B2-IT-DONE
