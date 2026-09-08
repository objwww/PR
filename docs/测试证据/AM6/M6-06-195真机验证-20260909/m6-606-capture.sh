set -e
# ============================================================================
# m6-606-capture.sh —— M6-06 退场预演 全只读取证（195 宿主执行）
#   A. 六面扫描日志回读（头部+裁定段）；B. drain barrier 重放（只读 SQL）；
#   C. 历史制品恢复复读（只读，RTO 重计时）；D. 当前退场姿态快照
#      （holmesgpt 容器态/健康/镜像 digest/.env 键名only/封存目录清单）。
# 纪律：零状态变更；.env 只列键名不回显值；bearer/口令不经输出面。
# ============================================================================
echo "===== A. six-face scan log ====="
wc -l /tmp/m6-606-scan.log
sed -n '1,6p' /tmp/m6-606-scan.log
echo '--- scan tail (waiver section) ---'
tail -30 /tmp/m6-606-scan.log

echo "===== B. drain barrier (replay, read-only) ====="
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1' < /tmp/m6-606-drain.sql

echo "===== C. restore-read replay (read-only, RTO re-time) ====="
sh /tmp/m6-606-restore.sh

echo "===== D. exit posture snapshot ====="
echo '--- holmesgpt container state ---'
docker ps -a --filter name=holmesgpt --format '{{.Names}} {{.Status}}'
echo '--- control-app health ---'
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- control-app image digest ---'
docker inspect deploy-control-app-1 --format '{{index .RepoDigests 0}}' 2>/dev/null || docker images --digests --format '{{.Repository}} {{.Digest}}' | grep control-app | head -2
echo '--- .env HOLMES/SHADOW/FALLBACK key NAMES only (values never printed) ---'
grep -E '^(APP_ALERT_SHADOW_HOLMES_ENABLED|APP_ALERT_FALLBACK_ENABLED|HOLMES_|APP_ALERT_HOLMES_)' /opt/build/pr/deploy/.env 2>/dev/null | sed 's/=.*$/=<redacted>/' || echo '(no deploy .env matches)'
echo '--- sealed rollback artifacts ---'
ls -la /opt/backups/pre-m607-holmes-removal/
cat /opt/backups/pre-m607-holmes-removal/holmesgpt-image-digest.txt
echo '--- alert project holmesgpt service still declared (pre-removal anchor) ---'
grep -c 'holmesgpt' /opt/build/pr/deploy/alert/docker-compose.yml
echo 'CAPTURE_M606_OK'
