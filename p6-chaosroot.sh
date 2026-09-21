#!/bin/sh
docker logs alert-arena-chaos-admin-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -B3 -A6 'Exception\|error' | grep -E 'Exception|Caused|at com|at dev' | head -10 | cut -c1-200
