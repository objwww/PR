#!/bin/sh
# fetch-image-195.sh —— 195 从 127 拉取摆渡镜像并装载（MIG-127 协作面，2026-09-21）
# 前置：127 已执行 scripts/ship-image-127.sh（WG 面 http 服务 30 分钟窗口内）
# 用法（在 195 执行）:  sh /opt/build/pr/scripts/fetch-image-195.sh
set -eu
OUT=/tmp/img-ferry.tgz
curl -s --max-time 2400 -o "$OUT" http://10.250.250.2:18222/img-ferry.tgz -w 'dl=%{http_code} bytes=%{size_download}\n'
[ "$(stat -c%s "$OUT")" -gt 1000000 ] || { echo "拉取异常：文件过小，检查 127 ferry 服务是否在窗口内"; exit 1; }
zcat "$OUT" | docker load
rm -f "$OUT"
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}} {{.CreatedSince}}' | head -5
