set -e
cd /opt/projects/pr_agent_it
export JAVA_HOME=/opt/jdk-21.0.12.1+1
set -o pipefail
/opt/maven/bin/mvn -s maven-settings-aliyun.xml -pl control-app -am verify -Dtest=PostgresHolmesShadowIT -Dsurefire.failIfNoSpecifiedTests=false 2>&1 \
    | tee /opt/projects/pr_agent_it/m6-m605-it.log \
    | grep -E 'Tests run|BUILD|ERROR' | tail -20
