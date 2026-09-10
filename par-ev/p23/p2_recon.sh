#!/bin/sh
# P2 前置只读勘察：容器名 / mounts / prometheus.yml glob / rules 目录 / 版本 / 磁盘内存
OUT=/tmp/p2_recon.txt
: > "$OUT"
{
echo "===== [1] docker ps -a (names/image/status) ====="
docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
echo
echo "===== [2] prometheus-like running containers ====="
docker ps --format '{{.Names}}' | grep -i prom
} >> "$OUT" 2>&1

PROM=$(docker ps --format '{{.Names}}' | grep -i prometheus | head -1)
{
echo "PROM_CONTAINER=$PROM"
echo
echo "===== [3] inspect mounts of $PROM ====="
docker inspect "$PROM" --format '{{range .Mounts}}{{.Type}}|{{.Source}}|{{.Destination}}|{{.Mode}}
{{end}}'
echo
echo "===== [3b] image ====="
docker inspect "$PROM" --format '{{.Config.Image}}'
echo
echo "===== [3c] promtool version (inside container) ====="
docker exec "$PROM" promtool --version 2>&1 | head -5
echo
echo "===== [4] host prometheus.yml ====="
cat /opt/build/pr/deploy/alert/prometheus/prometheus.yml 2>&1
echo
echo "===== [5] host rules dir ====="
ls -la /opt/build/pr/deploy/alert/prometheus/rules/ 2>&1
echo
echo "===== [6] df -h / ====="
df -h /
echo
echo "===== [7] free -m ====="
free -m
echo
echo "===== [8] current ALERTS (head) ====="
curl -s -G 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=ALERTS' | head -c 3000
echo
echo
echo "===== [9] container-internal rules path listing ====="
docker exec "$PROM" sh -c 'ls -la /etc/prometheus/rules/ 2>/dev/null; ls -la /etc/prometheus/ 2>/dev/null | head -20'
} >> "$OUT" 2>&1
echo "RECON_DONE"
