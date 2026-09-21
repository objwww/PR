#!/bin/sh
set -e
cd /opt/build/pr
echo '2984f1304740303d809023e2ae8ff82b  /tmp/p4-batch2c.tar.gz' | md5sum -c -
tar xzf /tmp/p4-batch2c.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD|ERROR' | tail -3
cd deploy
docker compose build control-app 2>&1 | tail -1
docker compose up -d control-app 2>&1 | tail -1
sleep 30
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- 镜像内 rounds 接线确认（宿主编译产物）---'
grep -c 'app.eval.rounds' /opt/build/pr/control-app/target/classes/com/objwww/pr/control/eval/application/EvalRunnerConfig.class
echo '--- 重启 worker（孤儿清扫将 worker_lost 终态化 run-10）---'
docker rm -f eval-worker-p4rt >/dev/null 2>&1 || true
sh /tmp/p4w.sh
echo '--- worker 启动后 run-10 状态 ---'
sleep 5
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select state||' '||coalesce(terminal_reason,'-') from eval_run where id::text like 'b19723c9%';"
