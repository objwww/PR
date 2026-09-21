#!/bin/sh
docker logs --since 40m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'chaos 会话处理失败' | tail -6
echo '--- 计数:'
docker logs --since 40m alert-order-arena-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -c 'chaos 会话处理失败'
