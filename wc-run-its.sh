#!/bin/sh
# WC 批 195 定向 IT：锁序/命令原子化/对账公平/对账语义/提交围栏（Testcontainers 真 PG）
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp test -pl control-app -am \
  -Dtest='PostgresCheckpointLockOrderIT,PostgresCommandAtomicityIT,PostgresRunReconcilerFairnessIT,PostgresRunReconcilerIT,PrimaryCheckpointCommitFenceIT,ExA2LeaseCancelFenceIT' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E 'Tests run|BUILD|ERROR' | tail -40
exit 0
