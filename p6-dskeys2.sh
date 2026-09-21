#!/bin/sh
echo '---litellm container env (model providers)---'
docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -viE 'key|secret|salt' | head -20
echo '---litellm mounts---'
docker inspect litellm-am3 --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}' 2>/dev/null
echo '---search DeepSeek keys in env files---'
grep -rl 'AGENT_MODEL_ID_DeepSeek\|MODEL_BASE_URL_DeepSeek' /opt/build/pr/ 2>/dev/null | head -5
