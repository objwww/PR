#!/bin/sh
# sync-src-127.sh —— 工作站源码同步到 127 构建面（MIG-127，2026-09-21）
# 在工作站 Git Bash 执行：sh scripts/sync-src-127.sh
# 九目录与父 pom 一个不能少（父 pom 挂全模块，漏了报 Child module does not exist）；
# .env 不入包（chmod 600 单独摆渡先例）；target/node_modules 不带（127 自行构建）。
set -eu
cd "$(dirname "$0")/.."
tar czf - pom.xml scripts shared-kernel control-app alert-web deploy order-arena arena-chaos-admin notify-app duty-adapter \
  --exclude='*/target' --exclude='*/node_modules' --exclude='*/dist' --exclude='.env' \
  | ssh -i ~/.ssh/hotel_deploy -o StrictHostKeyChecking=no root@117.72.208.68 \
    "cd /opt/build/pr && tar xzf - && echo synced"
echo "下一步（127 构建）：ssh 127 'cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1 && /opt/apache-maven-3.9.9/bin/mvn -q -pl control-app -am -DskipTests package'"
