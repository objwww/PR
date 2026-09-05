#!/usr/bin/env bash
T=$(curl -s 'https://ghcr.io/token?scope=repository:berriai/litellm:pull' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -s -H "Authorization: Bearer $T" 'https://ghcr.io/v2/berriai/litellm/tags/list' > /tmp/litellm_tags.json
echo "bytes=$(wc -c < /tmp/litellm_tags.json)"
echo "== main-v1.89* =="
grep -o 'main-v1\.89[0-9.]*[a-z-]*' /tmp/litellm_tags.json | sort -u | head
echo "== any v1.89* =="
grep -o '"[^"]*v1\.89[0-9.]*[a-z-]*"' /tmp/litellm_tags.json | sort -u | head
echo "== stable-ish recent =="
grep -o 'main-v1\.9[0-9][0-9.]*-stable' /tmp/litellm_tags.json | sort -u | tail -5
grep -o 'main-v1\.8[0-9][0-9.]*-stable' /tmp/litellm_tags.json | sort -u | tail -5
