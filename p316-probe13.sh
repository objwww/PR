#!/bin/sh
echo '=== alert-web 镜像创建时间 ==='
docker image inspect pr-agent/alert-web:0.0.1-SNAPSHOT --format '{{.Created}}'
echo '=== 运行 web 容器镜像 ID vs 最新 ==='
docker inspect deploy-web-1 --format '{{.Image}}'
docker image inspect pr-agent/alert-web:0.0.1-SNAPSHOT --format '{{.Id}}'
echo '=== 构建树上 MonitorView 是否含新区块 ==='
grep -c '分层延迟' /opt/build/pr/alert-web/src/views/MonitorView.vue || echo 0
echo '=== web 容器内 assets ==='
docker exec deploy-web-1 sh -c "ls /usr/share/nginx/html/assets | head -5"
