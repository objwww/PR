#!/bin/sh
grep -E '^AGENT_MODEL=|^OPENAI_COMPAT_BASE_URL=|^AGENT_MODEL_API_KEY=' /opt/build/pr/deploy/.env | sed -E 's/(API_KEY=).*/\1<set,len=redacted>/' | tr -d '\r'
echo '---config keys for route client---'
grep -rn 'app.model\|spring.ai' /opt/build/pr/deploy/docker-compose.yml | tr -d '\r' | head -12
