#!/bin/sh
# rr-bcrypt-debug.sh —— bcrypt 生成调试
JAR=$(ls /opt/build/pr/control-app/target/*SNAPSHOT*.jar 2>/dev/null | head -1)
echo "JAR=$JAR"
unzip -l "$JAR" 2>/dev/null | grep -E 'spring-security-crypto|BOOT-INF/lib/spring' | head -5
LIB=$(unzip -l "$JAR" | grep -oE 'BOOT-INF/lib/spring-security-crypto[^ ]*\.jar' | head -1)
echo "LIB=$LIB"
[ -n "$LIB" ] && unzip -p "$JAR" "$LIB" > /tmp/rr-sscrypto.jar && ls -la /tmp/rr-sscrypto.jar
/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/rr-sscrypto.jar /tmp/BcryptGen.java iso-op-pw-rr15 2>&1 | head -12
