#!/bin/sh
cd /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -B2 -A6 'ERROR.*\.java' | head -30
