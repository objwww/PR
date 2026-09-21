#!/bin/sh
# 探针：容器内对 prometheus 的 catalog 型查询时延（3 次）
for i in 1 2 3; do
  S=$(date +%s%N)
  wget -q -T 10 -O /dev/null "http://prometheus:9090/api/v1/label/__name__/values"
  RC=$?
  E=$(date +%s%N)
  MS=$(( (E - S) / 1000000 ))
  echo "probe$i rc=$RC ms=$MS"
done
