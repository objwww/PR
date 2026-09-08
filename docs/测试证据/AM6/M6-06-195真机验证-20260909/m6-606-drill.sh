set -e
echo '== M6-06 恢复演练预演（无 holmesgpt 容器生产面存续 + 旧制品恢复实跑） =='
cd /opt/build/pr/deploy
echo '== step1 .env 备份 + 双闸落 false（sanctioned kill-switch：M6-04 README 裁定面） =='
cp -a .env /opt/backups/pre-m606-dotenv-$(date +%Y%m%d%H%M%S).bak
ls -la /opt/backups/pre-m606-dotenv-*.bak | tail -1
sed -i 's/^APP_ALERT_SHADOW_HOLMES_ENABLED=.*/APP_ALERT_SHADOW_HOLMES_ENABLED=false/' .env
if grep -q '^APP_ALERT_FALLBACK_ENABLED=' .env; then
  sed -i 's/^APP_ALERT_FALLBACK_ENABLED=.*/APP_ALERT_FALLBACK_ENABLED=false/' .env
else
  printf '\n# M6-06 预演：fallback 铸造面 kill-switch（holmesgpt 停机前必须先关）\nAPP_ALERT_FALLBACK_ENABLED=false\n' >> .env
fi
echo -n 'shadow=false 次数(期望1): '; grep -c '^APP_ALERT_SHADOW_HOLMES_ENABLED=false' .env
echo -n 'fallback=false 次数(期望1): '; grep -c '^APP_ALERT_FALLBACK_ENABLED=false' .env
echo '== step2 先关闸再重启 control-app（闸生效面） =='
docker compose up -d control-app 2>&1 | tail -1
code=000
for i in $(seq 1 24); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
  if [ "$code" = "200" ]; then break; fi
  sleep 5
done
if [ "$code" != "200" ]; then echo 'FAIL: 闸后 control-app 未回健康'; exit 1; fi
echo "闸后 control-app health=200（attempt $i）"
echo '== step3 停 holmesgpt-am1（预演=无 holmesgpt 生产面） =='
T0=$(date +%s)
docker stop holmesgpt-am1
T1=$(date +%s)
echo "holmesgpt stop 耗时=$((T1-T0))s"
docker ps --format '{{.Names}} {{.Status}}' | grep -c holmesgpt || echo 'holmesgpt 已不在运行面'
echo '== step4 无 holmesgpt 下 control-app 存续观测（60s 窗） =='
sleep 30
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
echo "t+30s health=$code"
sleep 30
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true)
echo "t+60s health=$code"
if [ "$code" != "200" ]; then echo 'FAIL: 无 holmesgpt 下 control-app 未存续'; exit 1; fi
echo '== step5 旧路径制品恢复实跑（历史 HOLMES 报告+证据读面，RTO 计时） =='
T2=$(date +%s)
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -At' <<'EOF'
SELECT r.id || '|' || rp.id || '|' || length(rp.package_json)
  FROM rca_run r JOIN rca_report rp ON rp.run_id = r.id
 WHERE r.engine='HOLMES' AND r.state='SUCCEEDED'
 ORDER BY r.created_at ASC LIMIT 1;
EOF
T3=$(date +%s)
echo "旧制品读面 RTO=$((T3-T2))s（含 psql 冷连接）"
echo '== step6 镜像 digest 封存 + 旧 compose 归档（回滚制品，BA-34） =='
mkdir -p /opt/backups/pre-m607-holmes-removal
docker image inspect local/holmesgpt:am1-http --format '{{.Id}} {{join .RepoDigests ","}}' \
  > /opt/backups/pre-m607-holmes-removal/holmesgpt-image-digest.txt 2>/dev/null \
  || docker image inspect local/holmesgpt:am1-http --format '{{.Id}}' \
  > /opt/backups/pre-m607-holmes-removal/holmesgpt-image-digest.txt
cat /opt/backups/pre-m607-holmes-removal/holmesgpt-image-digest.txt
cp -a /opt/build/pr/deploy/alert/docker-compose.yml /opt/backups/pre-m607-holmes-removal/alert-compose-pre-removal.yml
cp -a /opt/build/pr/deploy/docker-compose.yml /opt/backups/pre-m607-holmes-removal/deploy-compose-pre-removal.yml
cp -a /opt/build/pr/deploy/.env /opt/backups/pre-m607-holmes-removal/dotenv-pre-removal.bak
chmod 600 /opt/backups/pre-m607-holmes-removal/dotenv-pre-removal.bak
ls -la /opt/backups/pre-m607-holmes-removal/
echo 'DRILL_M606_OK'
