#!/bin/sh
# ship-image-127.sh —— 127 构建面产出镜像摆渡到 195（MIG-127 协作面，2026-09-21）
# 用法（在 127 执行）:  sh /opt/build/pr/scripts/ship-image-127.sh pr-agent/control-app:0.0.1-SNAPSHOT
# 之后在 195 执行:      sh /opt/build/pr/scripts/fetch-image-195.sh
# 设计：127→195 无 ssh 通路（195 仅收 workstation 与既有 deploy 密钥），故走拉模式——
# 127 在 WG 面临时起 http 服务（30 分钟自动熄灭），195 主动 curl 拉取并 docker load。
set -eu
IMAGE="${1:?usage: ship-image-127.sh <image:tag>}"
OUT=/tmp/img-ferry.tgz
docker save "$IMAGE" | gzip > "$OUT"
ls -la "$OUT"
# 旧的 ferry 服务先熄灭（方括号技巧防自匹配）
pkill -f 'http[.]server 18222' 2>/dev/null || true
sleep 1
cd /tmp
# timeout 包裹：30 分钟后服务自动退出，不留常驻面
setsid nohup timeout 1800 python3 -m http.server 18222 --bind 10.250.250.2 >/tmp/img-ferry.log 2>&1 < /dev/null &
sleep 2
curl -s -o /dev/null -w "ferry-self=%{http_code}\n" http://10.250.250.2:18222/img-ferry.tgz
echo "就绪：到 195 执行  sh /opt/build/pr/scripts/fetch-image-195.sh"
