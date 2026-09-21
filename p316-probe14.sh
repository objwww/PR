#!/bin/sh
docker exec deploy-web-1 sh -c "grep -l '分层延迟' /usr/share/nginx/html/assets/MonitorView-*.js 2>/dev/null || echo NOT_FOUND"
docker exec deploy-web-1 sh -c "grep -o '分层延迟\|成本归因\|风险审计' /usr/share/nginx/html/assets/MonitorView-*.js 2>/dev/null | sort | uniq -c" || true
