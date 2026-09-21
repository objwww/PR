#!/bin/sh
# rr-lock-probe.sh —— 查 /tmp/rr1415.lock 持锁者 + 全 rr1415 相关进程
echo "== flock 试探 =="
flock -n /tmp/rr1415.lock true 2>&1; echo "flock-rc=$?"
ls -l /tmp/rr1415.lock 2>/dev/null || echo "lock 文件不存在"
echo "== fd 持有者扫描 =="
for d in /proc/[0-9]*/fd; do
  if ls -l "$d" 2>/dev/null | grep -q 'rr1415.lock'; then
    p=${d%/fd}
    printf 'HOLDER pid=%s cmd=%s\n' "$p" "$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null | cut -c1-100)"
  fi
done
echo "== 全部 rr1415 相关进程 =="
ps ax -o pid,ppid,stat,etime,cmd | grep rr1415 | grep -v grep
echo "== done =="
