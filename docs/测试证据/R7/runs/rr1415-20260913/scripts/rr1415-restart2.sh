#!/bin/sh
# rr1415-restart2.sh —— 终极清场：杀全部 runner/子进程 + rriso 全毁 + 单发
pkill -9 -f 'rr1415-ru[n]' 2>/dev/null
pkill -9 -f 'rr1415-injec[t]' 2>/dev/null
sleep 3
pgrep -af 'rr1415' | grep -v grep | grep -v 'bash -c' || echo "ALL-DEAD"
cd /opt/build/pr/rr-iso
# 资源级硬清场：compose down 依赖 rr-iso.env 插值，残破 env 会令 down 失败留下旧卷
# （旧卷密码 vs 新铸 secrets → migrate 认证死循环，2026-09-13 rr1415 实证）；
# 裸 rriso-net 为外联织物（litellm/prometheus/loki 挂点）保留不动
docker rm -f rriso-postgres-1 rriso-migrate-1 rriso-control-app-1 2>/dev/null
docker volume rm -f rriso_rriso-pg-data rriso_rriso-cas 2>/dev/null
docker network rm rriso_rriso-int 2>/dev/null
docker network rm rriso_rriso-net 2>/dev/null   # 旧 compose 内网命名遗留（external 改造后不再产生）
rm -f /opt/build/pr/rr-iso/rr-iso.secrets /opt/build/pr/rr-iso/rr-iso.posture   # 卷/秘密/姿态同寿命全清
docker ps -a --filter name=rriso --format '{{.Names}}' || echo "rriso 清空"
echo "单发："
rm -f /opt/build/pr-logs/rr1415-main.log
setsid nohup sh /opt/build/rr1415-run.sh > /opt/build/pr-logs/rr1415-main.log 2>&1 < /dev/null &
sleep 2
pgrep -f 'rr1415-ru[n]' | head -3
echo "GO"
