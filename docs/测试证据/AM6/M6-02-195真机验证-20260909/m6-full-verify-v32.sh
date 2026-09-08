set -e
cd /opt/projects/pr_agent_it
export JAVA_HOME=/opt/jdk-21.0.12.1+1
/opt/maven/bin/mvn -s maven-settings-aliyun.xml clean verify 2>&1 \
    | tee /opt/projects/pr_agent_it/m6-m602-verify.log \
    | grep -E 'Tests run:.*Failures|BUILD|ERROR' | tail -30
