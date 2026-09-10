#!/bin/sh
# P3-1: 磁盘全量对账数据导出（只读）
OUT=/tmp/p3_disk_base.txt
: > "$OUT"
{
echo "===== [1] df -h (all) ====="
df -h
echo
echo "===== [2] docker system df ====="
docker system df
echo
echo "===== [3] docker system df -v (full) ====="
docker system df -v
} >> "$OUT" 2>&1

{
echo "===== [4] dangling volumes (names) ====="
docker volume ls -qf dangling=true | tee /tmp/p3_dangling_volumes.txt
echo
echo "count:"
wc -l < /tmp/p3_dangling_volumes.txt
echo
echo "===== [5] all volumes ====="
docker volume ls
echo
echo "===== [6] running containers with mounts ====="
docker ps --format '{{.Names}}' | while read c; do
  echo "--- $c ---"
  docker inspect "$c" --format '{{range .Mounts}}{{.Type}}|{{.Name}}{{.Source}}|{{.Destination}}
{{end}}'
done
echo
echo "===== [7] all containers (ps -a) names+status ====="
docker ps -a --format '{{.Names}}\t{{.Status}}'
} >> "$OUT" 2>&1
echo "P3_BASE_DONE"
