#!/bin/sh
RID=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id from eval_run order by started_at desc limit 1" | tr -d '[:space:]')
echo "sample_run=$RID"
curl -s -b /tmp/p311.cookie -c /tmp/p311.cookie -o /dev/null http://127.0.0.1:8080/api/auth/csrf
T=$(grep XSRF-TOKEN /tmp/p311.cookie | awk '{print $NF}')
echo '=== 打标实验=有效 ==='
curl -s -b /tmp/p311.cookie -X POST "http://127.0.0.1:8080/api/eval/governance/runs/$RID/governance-tag" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"tag":"VALID"}'; echo
echo '=== 数据集打标=冒烟 ==='
curl -s -b /tmp/p311.cookie -X POST "http://127.0.0.1:8080/api/eval/governance/dataset-tiers" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{"name":"demo","version":"v1","tier":"SMOKE"}'; echo
echo '=== 读回 ==='
curl -s -b /tmp/p311.cookie "http://127.0.0.1:8080/api/eval/governance/run-tags" | head -c 300; echo
curl -s -b /tmp/p311.cookie "http://127.0.0.1:8080/api/eval/governance/dataset-tiers"; echo
echo '=== flyway 版本 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version from flyway_schema_history where success order by installed_rank desc limit 1;"
