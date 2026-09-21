#!/bin/sh
docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^LITELLM_MASTER_KEY='
