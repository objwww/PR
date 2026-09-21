#!/bin/sh
# B1-1：control-app 重启事实核对——StartedAt/RestartCount/镜像 digest/jar md5/CL-03 投影是否在部署 jar
echo "== 容器启动事实 =="
docker inspect deploy-control-app-1 --format "StartedAt={{.State.StartedAt}} RestartCount={{.RestartCount}} ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}"
docker inspect deploy-control-app-1 --format "Image={{.Image}}"
echo "== jar md5 =="
docker exec deploy-control-app-1 sh -c "md5sum /app/app.jar 2>/dev/null || md5sum /app/*.jar 2>/dev/null" || \
  docker exec deploy-control-app-1 sh -c "ls /app; find / -maxdepth 3 -name '*.jar' 2>/dev/null | head -5"
echo "== jar 内 ContextAssembler 是否含 projectLogs（CL-03 投影标记）=="
docker exec deploy-control-app-1 sh -c "unzip -p /app/app.jar BOOT-INF/classes/com/objwww/pr/control/alert/application/agent/ContextAssembler.class 2>/dev/null | strings | grep -c projectLogs" || echo "(unzip/路径失败——试 jar 名)"
echo "== 最近容器日志 30 行（谁/为何重启线索）=="
docker logs deploy-control-app-1 --tail 15 2>&1 | cut -c1-200
