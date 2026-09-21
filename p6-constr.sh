#!/bin/sh
docker logs alert-arena-chaos-admin-1 --since '2026-09-18T19:50:00Z' 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -iE 'duplicate|constraint|DataIntegrity' | head -6 | cut -c1-300
