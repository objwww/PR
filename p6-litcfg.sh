#!/bin/sh
echo '---litellm config.yaml---'
cat /opt/build/pr/deploy/alert/litellm/config.yaml 2>/dev/null | grep -vE 'api_key' | head -40
echo '---std worker judge config---'
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -o 'judge[^,]*' | head -3
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -oE 'AGENT_MODEL[^,]*' | head -5
