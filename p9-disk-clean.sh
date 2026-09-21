#!/bin/sh
# p9 磁盘清理·并行会话保护模式
# 只动：陈旧部署 staging 包、过期构建缓存、journal 上限
# 不动：带 tag 镜像、卷、容器、live-*(今日=并行会话)、ssc.jar/Gen.java(在用)、
#       env-backup-*、*hold*（待裁定）、今日/昨日新建文件
BEFORE=$(df -h / | tail -1 | awk '{print $4}')
echo "BEFORE free=$BEFORE"

echo '=== [1] 两个 3.4G 旧部署包（FUP-03b/04 已部署完毕的 staging）==='
ls -la /tmp/fup04-full.tar.gz /tmp/fup03b-full.tar.gz
rm -f /tmp/fup04-full.tar.gz /tmp/fup03b-full.tar.gz

echo '=== [2] /tmp 陈旧 jar（>24h 的 48MB 跑批用 jar）==='
find /tmp -maxdepth 1 -name '*.jar' -type f -size +20M -mtime +1 ! -name 'ssc.jar' -print -delete

echo '=== [3] /tmp 其它 >100MB 且 >24h 陈旧文件（保留 live-*、hold、env-backup、p8/p9 脚本）==='
find /tmp -maxdepth 1 -type f -size +100M -mtime +1 \
  ! -name 'live-*' ! -name '*hold*' ! -name 'env-backup-*' \
  ! -name 'p8*' ! -name 'p9*' ! -name 'ssc.jar' -print -delete

echo '=== [4] /opt/build/pr 根目录旧同步 staging 包（>24h，保留 hold）==='
find /opt/build/pr -maxdepth 1 -type f \( -name '*.tar' -o -name '*.tar.gz' -o -name '*.tgz' \) -mtime +1 \
  ! -name '*hold*' -print -delete
echo '--- m6-ev 子目录旧包:'
find /opt/build/pr/m6-ev -maxdepth 1 -type f \( -name '*.tar' -o -name '*.tar.gz' \) -mtime +1 ! -name '*hold*' -print -delete 2>/dev/null

echo '=== [5] 构建缓存（可重建，只拖慢下次构建）==='
docker builder prune -f 2>&1 | tail -1

echo '=== [6] journal 收缩到 50M ==='
journalctl --vacuum-size=50M 2>/dev/null | tail -1

AFTER=$(df -h / | tail -1 | awk '{print $4}')
echo "=== 完成 BEFORE=$BEFORE AFTER=$AFTER ==="
df -h / | tail -1
