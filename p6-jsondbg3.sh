#!/bin/sh
cd /opt/build/pr
sed -n '1,11p' /tmp/p6w.sh > /tmp/p6dbg2.sh
cat >> /tmp/p6dbg2.sh <<'EOF'
docker run --rm --entrypoint sh -e SPRING_APPLICATION_JSON="$SPRING_JSON" pr-agent/control-app:0.0.1-SNAPSHOT -c 'echo CONTAINER_SEES: $SPRING_APPLICATION_JSON | head -c 300'
EOF
sh /tmp/p6dbg2.sh 2>&1 | tail -2
