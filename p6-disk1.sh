#!/bin/sh
echo '---before---'
df -h / | tail -1
rm -f /tmp/*.tar.gz /tmp/probe*.jar /tmp/*.txt /tmp/p6env.json /tmp/rt2-content.json /tmp/ssc.jar.bak 2>/dev/null
ls /tmp/*.log 2>/dev/null | head -3
rm -f /tmp/env-backup-*.bak 2>/dev/null
echo '---docker disk usage---'
docker system df 2>/dev/null
echo '---journal---'
journalctl --disk-usage 2>/dev/null | tail -1
echo '---/tmp top---'
du -sh /tmp/* 2>/dev/null | sort -rh | head -8
echo '---after tmp clean---'
df -h / | tail -1
