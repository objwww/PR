#!/bin/sh
# p9 磁盘清理·先量后删（并行会话在，只动可重建/无归属物）
echo '=== 总览 ==='
df -h / | tail -1
docker system df
echo '=== 大头分布 ==='
du -sh /var/lib/docker/overlay2 /var/lib/docker/containers /var/lib/docker/buildkit 2>/dev/null
du -sh /tmp /var/log /opt/build/pr 2>/dev/null
echo '=== docker build cache 可回收 ==='
docker builder du 2>/dev/null | tail -3
echo '=== 容器 json 日志 TOP5 ==='
du -sh /var/lib/docker/containers/*/*-json.log 2>/dev/null | sort -rh | head -5
echo '=== /tmp 大文件 TOP10（看归属/年龄再定）==='
find /tmp -maxdepth 1 -type f -size +20M -printf '%s %TY-%Tm-%Td %p\n' 2>/dev/null | sort -rn | head -10 | awk '{printf "%.0fMB %s %s\n", $1/1048576, $2, $3}'
echo '=== /var/log 大文件 TOP5 ==='
find /var/log -maxdepth 2 -type f -size +20M -printf '%s %TY-%Tm-%Td %p\n' 2>/dev/null | sort -rn | head -5 | awk '{printf "%.0fMB %s %s\n", $1/1048576, $2, $3}'
echo '=== /opt/build/pr 内大文件 TOP8 ==='
find /opt/build/pr -maxdepth 2 -type f -size +30M -printf '%s %TY-%Tm-%Td %p\n' 2>/dev/null | sort -rn | head -8 | awk '{printf "%.0fMB %s %s\n", $1/1048576, $2, $3}'
echo '=== journal 占用 ==='
journalctl --disk-usage 2>/dev/null | tail -1
