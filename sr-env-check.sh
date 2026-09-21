#!/bin/sh
grep -nE 'DUTY_K_DEAD_WEBHOOK|CONTROL_BIND|CONTROL_PORT|WEB_BIND|WEB_PORT' /opt/build/pr/deploy/docker-compose.yml
echo '=== CHANGED vars (bak -> rebuilt) ==='
for v in $(grep -oE '^[A-Z_0-9]+' /opt/build/pr.bak-20260907T134128Z/deploy/.env | sort -u); do
  old="$(grep -m1 "^${v}=" /opt/build/pr.bak-20260907T134128Z/deploy/.env | cut -d= -f2-)"
  new="$(grep -m1 "^${v}=" /opt/build/.env.rebuilt | cut -d= -f2-)"
  [ "$old" != "$new" ] && echo "CHANGED: $v"
done
echo done
