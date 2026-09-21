#!/bin/sh
# rr-port-pick.sh —— 为 iso control-app 选一个 127.0.0.1 空闲高位端口
pkill -9 -f 'rr1415-ru[n]' 2>/dev/null
sleep 1
echo "== runner 残留 =="; pgrep -af 'rr1415' | grep -v grep | grep -v 'rr-port-pick' || echo none
for p in 18091 18092 18093 18094 18095 28180; do
  if ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":$p$"; then
    echo "$p OCCUPIED"
  else
    echo "$p FREE"
  fi
done
