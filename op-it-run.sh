#!/bin/sh
# OP 批真机 IT：PostgresOpBatchIT(6) + CL 批三 IT（NOT_RUN 清账）
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp -pl control-app verify \
  -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='PostgresOpBatchIT,PostgresCompactionAttemptIT,PostgresRunSkillBindingIT,PrimaryCheckpointCommitFenceIT' \
  -Dfailsafe.failIfNoSpecifiedTests=false 2>&1 | tail -60
echo OP-IT-DONE
