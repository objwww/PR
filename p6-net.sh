#!/bin/sh
echo '---litellm-am3 networks---'
docker inspect litellm-am3 --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null || docker ps --format '{{.Names}}' | grep -i litellm
echo '---probe from worker net---'
docker run --rm --network alert-net busybox:latest sh -c 'wget -q -O- --timeout=3 http://litellm-am3:4000/health 2>&1 | head -c 200 || echo UNREACHABLE-alert-net' 2>/dev/null || echo 'probe failed'
docker run --rm --network eval-mgmt busybox:latest sh -c 'wget -q -O- --timeout=3 http://litellm-am3:4000/health 2>&1 | head -c 200 || echo UNREACHABLE-eval-mgmt' 2>/dev/null || echo 'probe2 failed'
