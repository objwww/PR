#!/bin/sh
cd /opt/build/pr/deploy
K=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
docker run --rm --network alert-net curlimages/curl:latest -s -X POST http://litellm-am3:4000/v1/chat/completions \
  -H "Authorization: Bearer $K" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v3","messages":[{"role":"user","content":"只输出JSON: {\"answers\":[{\"id\":\"Q1\",\"yes\":true}]}"}],"temperature":0,"max_tokens":2048}' 2>/dev/null | head -c 500
