#!/usr/bin/env bash
echo "== hub litellm/litellm latest 25 (any) =="
curl -s 'https://hub.docker.com/v2/repositories/litellm/litellm/tags?page_size=25' \
  | grep -o '"name":"[^"]*"' | head -26
echo "== hub litellm/litellm name=1.89 =="
curl -s 'https://hub.docker.com/v2/repositories/litellm/litellm/tags?page_size=25&name=1.89' \
  | grep -o '"name":"[^"]*"' | head -26
echo "== hub litellm team page check =="
curl -s -o /dev/null -w '%{http_code}\n' 'https://hub.docker.com/v2/repositories/litellm/litellm/'
