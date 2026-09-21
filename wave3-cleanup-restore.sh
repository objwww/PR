#!/bin/sh
set -e
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "delete from conclusion_feedback where actor='human:probe5'" 
echo "probe rows cleaned"
cd /opt/build/pr/deploy
BK=/tmp/env-backup-wave3-20260916T170929
cp "$BK" .env
[ "$(md5sum < .env | cut -d' ' -f1)" = "$(md5sum < "$BK" | cut -d' ' -f1)" ] && echo 'RESTORED_OK'
docker compose up -d control-app >/dev/null
code=000
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 10
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
  [ "$code" = "200" ] && break
done
echo "final-health=$code"
