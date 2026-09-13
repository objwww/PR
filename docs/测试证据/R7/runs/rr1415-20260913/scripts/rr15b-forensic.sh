#!/bin/sh
# rr15b-forensic.sh —— RR15-B FAIL 现场取证：18080 监听者 / B 批日志 / compose 痕迹
echo "== 18080 监听者 =="
ss -ltnp 2>/dev/null | grep 18080 || echo "无监听"
echo "== 谁在 18080 应答 =="
curl -s -m 5 -o /tmp/rr18080.out -w 'code=%{http_code} size=%{size_download}\n' http://127.0.0.1:18080/actuator/health || echo curl-fail
head -c 300 /tmp/rr18080.out 2>/dev/null; echo
echo "== rr15b-app.log =="
wc -c /opt/build/pr-logs/rr1415/rr15b-app.log; head -5 /opt/build/pr-logs/rr1415/rr15b-app.log 2>/dev/null
echo "== rr15b-up.log 尾部 =="
tail -8 /opt/build/pr-logs/rr1415/rr15b-up.log
echo "== 主日志尾 =="
tail -8 /opt/build/pr-logs/rr1415-main.log
echo "== iso 容器 =="
docker ps -a --filter name=rriso --format '{{.Names}} {{.Status}}'
echo "== rr14 K1 health 试探 =="
curl -s -m 5 -o /dev/null -w 'k1-health=%{http_code}\n' http://127.0.0.1:18080/actuator/health
