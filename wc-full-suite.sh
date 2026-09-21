#!/bin/sh
# WC 批 195 全量真值：mvn test 全跑（含全部 PG IT；Testcontainers）
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp test -pl control-app -am > /tmp/wc-full-suite.log 2>&1
code=$?
echo "exit_code=$code"
grep -E 'Tests run: [0-9]+, Failures' /tmp/wc-full-suite.log | tail -3
grep -E 'BUILD (SUCCESS|FAILURE)' /tmp/wc-full-suite.log | tail -1
exit 0
