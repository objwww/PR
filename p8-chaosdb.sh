#!/bin/sh
echo "--- containers:"
docker ps --format '{{.Names}}  {{.Image}}'
echo "--- arena-chaos-admin env (db related):"
docker inspect arena-chaos-admin --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -iE 'db|jdbc|postgres|url|datasource' | sed 's/PASSWORD=.*/PASSWORD=***/'
