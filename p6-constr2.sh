#!/bin/sh
docker logs alert-arena-chaos-admin-1 --since '2026-09-18T19:54:00Z' 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -B1 -A3 'Exception' | head -30 | cut -c1-260
