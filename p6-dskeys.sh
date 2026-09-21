#!/bin/sh
echo '---.env DeepSeek keys---'
grep -E 'DeepSeek|DEEPSEEK' /opt/build/pr/deploy/.env | sed -E 's/(API_KEY=....).*/\1**/' | tr -d '\r'
echo '---compose mapping---'
grep -n 'DeepSeek\|DEEPSEEK' /opt/build/pr/deploy/docker-compose.yml | tr -d '\r' | head -10
echo '---agents on the eval model path---'
grep -E '^AGENT_MODEL=' /opt/build/pr/deploy/.env | tr -d '\r'
