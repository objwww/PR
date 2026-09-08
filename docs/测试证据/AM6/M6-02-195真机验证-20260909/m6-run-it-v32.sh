set -e
cd /opt/projects/pr_agent_it
export JAVA_HOME=/opt/jdk-21.0.12.1+1
/opt/maven/bin/mvn -s maven-settings-aliyun.xml -pl control-app -am verify \
    -Dtest=NoopMatchAll -Dit.test=PostgresEngineComparisonIT \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false 2>&1 \
    | tee /opt/projects/pr_agent_it/m6-m602-it.log \
    | grep -E 'Tests run|FAIL|ERROR|BUILD' | tail -25
