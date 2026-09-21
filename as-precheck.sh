#!/bin/sh
# 部署前预检：只读非敏感姿态键（allowlist/enabled/mode），零秘密回显
echo '=== .env 姿态键（非敏感） ==='
grep -E 'PRIMARY_TOOL_ALLOWLIST|PRIMARY_ENABLED|COMPACTION_ENABLED|COMPACTION_MODE|INPUTCAPTURE|AGENT_MODEL=' /opt/build/pr/deploy/.env 2>/dev/null | grep -v -i 'key\|token\|bearer\|password\|secret' || echo '(.env 无匹配键，走缺省)'
echo '=== 容器实env（非敏感） ==='
docker exec deploy-control-app-1 sh -c 'env' 2>/dev/null | grep -E 'APP_ALERT_R7_PRIMARY_TOOL_ALLOWLIST|APP_ALERT_R7_PRIMARY_ENABLED|APP_ALERT_R7_COMPACTION' | grep -v -i 'key\|token\|bearer\|password\|secret' || echo '(容器无映射，走缺省)'
echo '=== 当前健康/版本 ==='
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where success order by installed_rank desc limit 1;"
echo '=== compose 文件含 tool-allowlist 覆写? ==='
grep -rn 'tool-allowlist\|TOOL_ALLOWLIST' /opt/build/pr/deploy/*.yml 2>/dev/null || echo '(compose 无覆写)'
