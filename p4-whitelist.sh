#!/bin/sh
# P4 白名单 bundle 资格授予（与 151 同机制：服务契约面无 HTTP 端点，insert-only 如实入账）
# + RELEASE 线 CAS 激活
set -e
cd /opt/build/pr/deploy
TOK=$(grep '^APP_RELEASE_API_BEARER=' .env | cut -d= -f2- | tr -d '\r"')
CAND=afd79ffed08f85f2f83fc340e190105d1ea3b1a7f376b684187cc84c979ffe6e
BASE=dd04382d95962b7cfb266d1450f9ab6af20c79de87323f8680838446b15159d1

echo '---grant qualification (insert-only)---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "insert into release_qualification (
    id, candidate_digest, baseline_digest, dataset_manifest_digest, runner_version,
    grader_version, quality_verdict, usage_status, granted_scope, granted_by, granted_at
) values (
    gen_random_uuid(), '$CAND', '$BASE',
    encode(sha256(convert_to('redteam-ds:rt-v2:v142','UTF8')),'hex'),
    'redteam-seed.v2', 'grader-p4-redteam-v2', 'PASS', 'MATCHED',
    'p4-redteam-v2-whitelist：仅新增五条红队 crafted alertname 白名单（不改变既有路由面）',
    'machine:release-line', now());"

echo '---activate (expectedActiveRevision=151)---'
curl -s -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/config-bundles/$CAND/activate" \
  -d '{"expectedActiveRevision":151}' -w '\nhttp=%{http_code}\n'

echo '---active revision---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select b.revision||' '||left(b.bundle_digest,12) from config_bundle b join config_bundle_active a on a.bundle_digest=b.bundle_digest where a.id=1;"
