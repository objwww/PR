#!/bin/sh
# 重跑 T20/T21 + SkillCandidateIT（195 真 PG）
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp -pl control-app -DskipTests test-compile \
    -Dmaven.compiler.useIncrementalCompilation=false > /tmp/rv-it2-compile.log 2>&1
grep -E '^\[INFO\] BUILD' /tmp/rv-it2-compile.log
mvn -B -ntp -pl control-app \
    -Dit.test='PostgresDelegationReceiptConcurrencyIT,PostgresSkillCandidateIT' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='PostgresDelegationReceiptConcurrencyIT,PostgresSkillCandidateIT' \
    test-compile failsafe:integration-test failsafe:verify > /tmp/rv-it2-run.log 2>&1
grep -E 'Tests run|BUILD' /tmp/rv-it2-run.log | tail -8
echo IT-RERUN-DONE
