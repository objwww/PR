#!/bin/sh
grep -E 'LITELLM|MASTER' /opt/build/pr/deploy/.env | sed -E 's/(KEY=)(....).*/\1\2**/' | tr -d '\r'
echo '---litellm env in container---'
docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -iE 'master|salt|store' | sed -E 's/(=....).*/\1**/'
