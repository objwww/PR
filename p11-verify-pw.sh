#!/bin/sh
echo '=== [1] Demo#0917 持久口令登录验证 ==='
cd /opt/build/pr/deploy
J=/tmp/probe-pw.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -o /dev/null -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=operator" --data-urlencode "password=Demo#0917" \
  -w 'login_Demo0917=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
echo '=== [2] 部署的前端 bundle 是否含预填账号 ==='
INDEX=$(curl -s http://127.0.0.1:8090/ | grep -oE 'assets/[a-zA-Z0-9_.-]+\.js' | grep -i login | head -1)
echo "login_chunk=$INDEX"
if [ -n "$INDEX" ]; then
  curl -s "http://127.0.0.1:8090/$INDEX" | grep -c 'Demo#0917'
else
  echo '按 chunk 名未定位到 login，改查所有 assets:'
  for f in $(curl -s http://127.0.0.1:8090/ | grep -oE 'assets/[a-zA-Z0-9_.-]+\.js' | sort -u); do
    C=$(curl -s "http://127.0.0.1:8090/$f" | grep -c 'Demo#0917')
    [ "$C" != "0" ] && echo "$f => $C"
  done
  echo 'done'
fi
