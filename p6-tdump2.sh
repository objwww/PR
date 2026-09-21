#!/bin/sh
NSPID=$(docker inspect eval-worker-std --format '{{.State.Pid}}')
kill -QUIT $NSPID
sleep 4
docker logs eval-worker-std --since 1m 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -A 30 '"main"' | grep -E 'at com.objwww|java.lang.Thread.State|waiting|sleep|socket' | head -25 | cut -c1-170
