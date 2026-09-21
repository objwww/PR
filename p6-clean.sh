#!/bin/sh
df -h /tmp | tail -1
rm -f /tmp/full-src.tar.gz /tmp/sync2.tar.gz /tmp/p4-batch*.tar.gz /tmp/p5-batch.tar.gz /tmp/p6-batch.tar.gz /tmp/p6-batch2*.tar.gz /tmp/p7-batch.tar.gz /tmp/p7b-guards.tar.gz /tmp/fup03-full.tar.gz /tmp/p4-fix-batch.tar.gz
echo '---after cleanup---'
df -h /tmp | tail -1
