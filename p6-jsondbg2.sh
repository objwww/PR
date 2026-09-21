#!/bin/sh
cd /opt/build/pr
sed -n '1,11p' /tmp/p6w.sh > /tmp/p6dbg.sh
echo 'echo DEBUG: $SPRING_JSON' >> /tmp/p6dbg.sh
echo 'echo LEN: ${#SPRING_JSON}' >> /tmp/p6dbg.sh
sh /tmp/p6dbg.sh 2>&1 | tail -3 | cut -c1-500
