#!/bin/sh
set -u
echo "== 0) 窗口静默 =="
if pgrep -f "e2e-b2-cl0[6].sh|b2-cl06-retr[y].sh" >/dev/null 2>&1; then echo "DRIVER-RUNNING"; pgrep -af "e2e-b2-cl0[6].sh|b2-cl06-retr[y].sh"; exit 1; fi
echo "WINDOW-QUIET"
echo "== 1) driver 副本同步 + 语法 =="
cp /opt/build/e2e-b2-cl06.sh /opt/build/b2tree/e2e-b2-cl06.sh
sed -i 's/\r$//' /opt/build/b2tree/e2e-b2-cl06.sh
sh -n /opt/build/b2tree/e2e-b2-cl06.sh && echo "syntax-ok" || { echo "FAIL: 语法"; exit 1; }
grep -c "首行须为无父最低版" /opt/build/b2tree/e2e-b2-cl06.sh
echo "== 2) 上一轮尝试日志备份 =="
mkdir -p /opt/build/pr-logs/b2cl06-v4b
cp -a /opt/build/pr-logs/b2-cl06-a0-try*.log /opt/build/pr-logs/b2cl06-v4b/ 2>/dev/null || true
ls -1 /opt/build/pr-logs/b2cl06-v4b/
echo "== 3) flagd 故障确保 + 指标面门 =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'100%'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
sleep 20
ES=$(docker exec prometheus-am0 wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=rpc_client_call_duration_seconds_count%7Berror_type%3D%22UNKNOWN%22%7D' 2>/dev/null | python3 -c "
import sys,json;d=json.load(sys.stdin);rs=d['data']['result']
print(len(rs))")
echo "error series count=${ES:-parse-fail}"
[ "${ES:-0}" -ge 1 ] || { echo "FAULT-NOT-VISIBLE"; exit 1; }
echo "== 4) 发射 retry 包裹器（c 轮）=="
nohup sh /opt/build/b2-cl06-retry.sh > /opt/build/pr-logs/b2-cl06-retry-v4c.log 2>&1 &
echo "retry-v4c pid=$!"
