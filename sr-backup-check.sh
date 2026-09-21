#!/bin/sh
echo '=== crontab ==='
crontab -l 2>/dev/null | grep -v '^#' || echo '(no crontab)'
echo '=== deploy/backups 现状 ==='
ls -la /opt/build/pr/deploy/backups 2>/dev/null || echo 'deploy/backups 不存在'
echo '=== /opt/build/backups（顶层） ==='
ls -la /opt/build/backups 2>/dev/null | head -12 || echo '不存在'
echo '=== bak 快照里的 backups ==='
ls -la /opt/build/pr.bak-20260907T134128Z/backups 2>/dev/null | head -12
echo '=== 全机 pr_agent dump 搜索 ==='
find /opt/build -maxdepth 4 -name 'pr_agent-*.dump' 2>/dev/null | head -20
