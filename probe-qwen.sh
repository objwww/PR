#!/bin/sh
# 探针：经 litellm 内网面直调 qwen3-max-preview 一条最小消息（密钥从 .env 读，不回显）
KEY=$(grep -E '^AGENT_MODEL_API_KEY=' /opt/build/pr/deploy/.env | head -1 | cut -d= -f2-)
docker exec deploy-control-app-1 sh -c "wget -q -O- --header=\"Authorization: Bearer $KEY\" --header=\"Content-Type: application/json\" --post-data='{\"model\":\"qwen3-max-preview\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8}' http://litellm-am3:4000/v1/chat/completions 2>/dev/null" | head -c 220
echo
