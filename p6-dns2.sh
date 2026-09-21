#!/bin/sh
docker run --rm --network eval-mgmt busybox:latest sh -c 'wget -q -O- --timeout=3 http://arena-chaos-admin:8080/actuator/health 2>&1 | head -c 80; echo; wget -q -O- --timeout=3 http://flagd-admin:8081/actuator/health 2>&1 | head -c 80; echo; wget -q -O- --timeout=3 http://litellm-am3:4000/health/liveliness 2>&1 | head -c 60' 2>&1
