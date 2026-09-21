#!/bin/sh
grep -c '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/.env && echo CHAOS_TOKEN_SET
# 可达性
docker run --rm --network alert-net busybox:latest sh -c 'wget -q -O- --timeout=3 http://arena-chaos-admin:8080/actuator/health 2>&1 | head -c 60; echo; wget -q -O- --timeout=3 http://flagd-admin:8081/actuator/health 2>&1 | head -c 60' 2>&1
