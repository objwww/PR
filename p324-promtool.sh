#!/bin/sh
set -e
cd /opt/build/pr/deploy/alert
docker run --rm --entrypoint promtool -v /opt/build/pr/deploy/alert:/w -w /w \
  quay.io/prometheus/prometheus:v3.13.1 test rules prometheus/arena-business-rules-test.yml
