#!/bin/sh
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E 'control-app|web'
echo '---control-app log---'
docker logs deploy-control-app-1 --tail 25 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | cut -c1-220
