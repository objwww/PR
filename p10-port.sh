#!/bin/sh
docker logs alert-arena-chaos-admin-1 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -iE 'Tomcat started|Netty started|on port' | tail -2
