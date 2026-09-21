#!/bin/sh
set -u
echo "== 0) 窗口静默 =="
if pgrep -f "e2e-b2-cl06.sh|b2-cl06-retry.sh" >/dev/null 2>&1; then echo "DRIVER-RUNNING"; exit 1; fi
echo "WINDOW-QUIET"

echo "== 1) v3 时代日志备份（防 tryN 覆写）=="
mkdir -p /opt/build/pr-logs/b2cl06-v3
cp -a /opt/build/pr-logs/b2-cl06-a0.log /opt/build/pr-logs/b2cl06-a0-r*.log /opt/build/pr-logs/b2-cl06-a0-try*.log /opt/build/pr-logs/b2cl06-v3/ 2>/dev/null || true
ls -1 /opt/build/pr-logs/b2cl06-v3/

echo "== 2) flagd 故障确保（幂等重打）+ 指标面前置门 =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'100%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
sleep 30
ES=$(docker exec prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%7D' 2>/dev/null | python3 -c "
import sys,json;d=json.load(sys.stdin);rs=d['data']['result']
print(len(rs))")
echo "error series count=${ES:-parse-fail}"
[ "${ES:-0}" -ge 1 ] || { echo "FAULT-NOT-VISIBLE"; exit 1; }

echo "== 3) 发射 retry 包裹器（v4 override 由包装器自行重挂）=="
nohup sh /opt/build/b2-cl06-retry.sh > /opt/build/pr-logs/b2-cl06-retry-v4.log 2>&1 &
echo "retry-v4 pid=$!"
