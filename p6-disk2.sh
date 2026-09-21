#!/bin/sh
echo '---before---'
df -h / | tail -1
echo '---top consumers---'
du -sh /opt/build/pr/control-app/target /opt/build/pr/alert-web/node_modules /var/lib/docker /var/log 2>/dev/null | sort -rh | head -6
echo '---prune---'
docker image prune -a -f 2>&1 | tail -1
docker builder prune -af 2>&1 | tail -1
rm -rf /opt/build/pr/control-app/target/classes /opt/build/pr/control-app/target/test-classes /opt/build/pr/control-app/target/surefire-reports 2>/dev/null
journalctl --vacuum-size=50M 2>/dev/null | tail -1
echo '---after---'
df -h / | tail -1
