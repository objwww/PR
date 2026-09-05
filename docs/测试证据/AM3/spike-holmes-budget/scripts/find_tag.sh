#!/usr/bin/env bash
# find an existing litellm proxy image tag (hub API + ghcr raw)
echo "== HUB tags matching 1.89 =="
curl -s 'https://hub.docker.com/v2/repositories/litellm/litellm/tags?page_size=50&name=1.89' \
  | tr '{' '\n' | grep -o '"name":"[^"]*"' | head -20
echo "== HUB tags matching stable (first 20) =="
curl -s 'https://hub.docker.com/v2/repositories/litellm/litellm/tags?page_size=20&name=stable' \
  | tr '{' '\n' | grep -o '"name":"[^"]*"' | head -20
echo "== GHCR =="
T=$(curl -s 'https://ghcr.io/token?scope=repository:berriai/litellm:pull' | sed 's/.*"token":"\([^"]*\)".*/\1/')
echo "token_len=${#T}"
curl -s -H "Authorization: Bearer $T" 'https://ghcr.io/v2/berriai/litellm/tags/list' | head -c 400
echo
