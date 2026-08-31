#!/usr/bin/env bash
# ============================================================================
# DP-01~05 部署验证（M0-T18，docs/M0-技术方案.md §12 L5）
# DP-11~14 部署验证（M1-T09，docs/M1-技术方案.md §11）：
#   DP-11 V3 权限矩阵实库断言；DP-12 三 worker 心跳+重启自愈；
#   DP-14 杀 control 半截处理→重放恰好一次。DP-13（真实仓库 draft 闭环）
#   需真实模式单独执行，见 deploy/dp13-real-draft.sh。
# DP-05/DP-14 依赖 stub 模式（GITHUB_API_BASE 指 github-stub）与
# wiremock 固定 PR 元数据映射（head/base SHA 与本脚本负载一致）。
# 在 195 服务器 compose 项目目录 /opt/build/pr/deploy 执行：bash smoke-test.sh
# （持久态 .env/keys 在 /opt/projects/pr_agent，经符号链接接入本目录，见 README）
# （DP-02/03 为破坏性注入用例，脚本内自动恢复；全量顺序 DP-01 → DP-05）
# 证据落 smoke-evidence/<run-ts>/（断言输出 + inspect/日志/stub journal 摘录）。
# 出口码：全 PASS = 0；任一 FAIL = 1。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"

EVIDENCE="smoke-evidence/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$EVIDENCE"
SUMMARY="$EVIDENCE/summary.txt"
: > "$SUMMARY"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  [PASS] $*" | tee -a "$SUMMARY"; }
bad() { FAIL=$((FAIL + 1)); echo "  [FAIL] $*" | tee -a "$SUMMARY"; }
assert_eq() { # <描述> <实际> <期望>
    if [ "$2" = "$3" ]; then ok "$1（=$2）"; else bad "$1：实际=[$2] 期望=[$3]"; fi
}
assert_contains() { # <描述> <文件> <模式>
    if grep -qF "$3" "$2" 2>/dev/null; then ok "$1"; else bad "$1：$2 不含 [$3]"; fi
}

psql() { docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc "$1"; }

# wait_for <描述> <超时秒> <命令...>：命令退出码 0 即就绪（命令每轮重新执行）
wait_for() {
    local desc="$1" timeout="$2"; shift 2
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        if "$@" >/dev/null 2>&1; then echo "  [就绪] $desc"; return 0; fi
        sleep 3
    done
    echo "  [超时] $desc（>${timeout}s）"; return 1
}

control_http_code() { curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary '{}' -H 'Content-Type: application/json' -H 'X-GitHub-Event: pull_request' -H 'X-GitHub-Delivery: alive-probe' http://127.0.0.1:"${CONTROL_PORT:-8080}"/webhooks/github; }
# 探针带 body + Event/Delivery 头的原因：空 body 或缺必需头在 Spring 侧是 400
# （参数解析先于验签），带齐头但无签名才走到验签 401——401 才是"web 已起且 fail-closed"的确定语义
control_alive() { [ "$(control_http_code)" = 401 ]; }
publisher_started() { docker compose logs --no-color publisher-app 2>/dev/null | grep -q "Started PublisherApplication"; }
pg_healthy() { [ "$(docker inspect "$(docker compose ps -q postgres)" -f '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }
migrate_done() { [ "$(docker inspect "$(docker compose ps -a -q migrate)" -f '{{.State.Status}}' 2>/dev/null)" = "exited" ]; }
dp02_failed() { docker logs dp02-control 2>&1 | grep -q "启动自检失败"; }
dp03_failed() { docker compose logs --no-color --since "$DP03_SINCE" control-app 2>/dev/null | grep -q "持有 UPDATE 权限"; }
dp05_confirmed() { [ "$(psql "select count(*) from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR05_NUM and oc.state='CONFIRMED'" | tr -d '[:space:]')" = "2" ]; }

# ---------------------------------------------------------------- preflight
echo "== preflight ==" | tee -a "$SUMMARY"
[ -f .env ] || { echo "缺 .env（见 README 步骤 1）"; exit 1; }
set -a; . ./.env; set +a
[ -f keys/github-app-key.pem ] || { echo "缺 keys/github-app-key.pem"; exit 1; }
# 私钥宿主文件不得带任何写位（publisher 启动自检断言只读；0444 只读挂载之上再加一层）
KEY_PERM=$(stat -c '%a' keys/github-app-key.pem)
case "$KEY_PERM" in *2*|*3*|*6*|*7*) echo "keys/github-app-key.pem 权限 $KEY_PERM 含写位，先 chmod 444"; exit 1;; esac
ls ../control-app/src/main/resources/db/migration/V1__m0_schema.sql \
   ../control-app/src/main/resources/db/migration/V2__grants.sql >/dev/null \
    || { echo "缺迁移 SQL（compose migrate 直挂 ../control-app/src/main/resources/db/migration）"; exit 1; }
ls wiremock/__files/stub-tarball.tar.gz >/dev/null || { echo "缺 wiremock stub 夹具"; exit 1; }
docker image inspect pr-agent/control-app:0.0.1-SNAPSHOT >/dev/null 2>&1 \
    || { echo "缺镜像 pr-agent/control-app（README 步骤 2/3：先 package 再 docker build）"; exit 1; }
docker image inspect pr-agent/publisher-app:0.0.1-SNAPSHOT >/dev/null 2>&1 \
    || { echo "缺镜像 pr-agent/publisher-app（README 步骤 2/3：先 package 再 docker build）"; exit 1; }
echo "  .env / 私钥(mode=$KEY_PERM) / 迁移 SQL / stub 夹具 / 两镜像 齐备" | tee -a "$SUMMARY"

# ---------------------------------------------------------------- DP-01
echo "== DP-01 一键起栈 + 启动自检 ==" | tee -a "$SUMMARY"
docker compose up -d > "$EVIDENCE/dp01-up.log" 2>&1
echo "  compose up 出口码=$?" >> "$EVIDENCE/dp01-up.log"

wait_for "postgres healthy" 90 pg_healthy || true
wait_for "migrate one-shot 退出" 120 migrate_done || true
MIGRATE_CID=$(docker compose ps -a -q migrate)
MIGRATE_EXIT=$(docker inspect "$MIGRATE_CID" -f '{{.State.ExitCode}}' 2>/dev/null || echo missing)
docker logs "$MIGRATE_CID" > "$EVIDENCE/dp01-migrate.log" 2>&1 || true
assert_eq "migrate one-shot 退出码" "$MIGRATE_EXIT" "0"
# TB-06/INC-29：迁移日志断言改双态——首启容器随 compose down 销毁后，
# 稳态轮次日志恒为 "up to date"；真正的门禁是 flyway 版本 == 迁移文件最大版本
if grep -qF "Migrating schema" "$EVIDENCE/dp01-migrate.log"; then
    ok "migrate 日志：首启执行迁移（Migrating schema）"
elif grep -qF "up to date" "$EVIDENCE/dp01-migrate.log"; then
    ok "migrate 日志：稳态无迁移（up to date，首启容器已销毁属预期）"
else
    bad "migrate 日志双态均不匹配：既无 [Migrating schema] 亦无 [up to date]"
fi
EXPECT_V=$(ls ../control-app/src/main/resources/db/migration/V*.sql | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1)
ACTUAL_V=$(psql "select max(version::int) from flyway_schema_history where success" | tr -d '[:space:]')
assert_eq "flyway 已应用版本 == 迁移文件最大版本" "$ACTUAL_V" "$EXPECT_V"

wait_for "control webhook 401（未签名 fail-closed = 存活）" 150 control_alive || true
wait_for "publisher Started" 150 publisher_started || true
assert_eq "control 未签名 POST → 401" "$(control_http_code)" "401"
docker compose logs --no-color control-app > "$EVIDENCE/dp01-control.log" 2>&1
docker compose logs --no-color publisher-app > "$EVIDENCE/dp01-publisher.log" 2>&1
assert_contains "control 日志含『启动自检通过』" "$EVIDENCE/dp01-control.log" "启动自检通过"
assert_contains "publisher 日志含『启动自检通过』" "$EVIDENCE/dp01-publisher.log" "启动自检通过"
docker compose ps --format '{{.Service}}\t{{.Status}}' | tee "$EVIDENCE/dp01-ps.txt"
for svc in postgres control-app publisher-app github-stub; do
    st=$(docker compose ps --format '{{.Status}}' "$svc")
    case "$st" in *running*|*Up*) ok "$svc 进程存活（$st）";; *) bad "$svc 未存活：$st";; esac
done

# ---------------------------------------------------------------- DP-02
echo "== DP-02 给 control 注入写凭证 → 拒绝启动（AFT-02 动态） ==" | tee -a "$SUMMARY"
docker rm -f dp02-control >/dev/null 2>&1 || true
docker compose run -d -T --no-deps --name dp02-control \
    -e GITHUB_WRITE_TOKEN=dp02-fake-token control-app > /dev/null 2>&1
wait_for "dp02-control 自检失败日志" 90 dp02_failed || true
docker logs dp02-control > "$EVIDENCE/dp02-control.log" 2>&1
assert_contains "日志含『启动自检失败』" "$EVIDENCE/dp02-control.log" "启动自检失败"
assert_contains "日志点名 GITHUB_WRITE_TOKEN（只点名不含值）" "$EVIDENCE/dp02-control.log" "GITHUB_WRITE_TOKEN"
if grep -q "dp02-fake-token" "$EVIDENCE/dp02-control.log"; then bad "凭证值泄漏进日志"; else ok "凭证值未进日志"; fi
# TB-03 修复：compose run 一次性容器不继承 unless-stopped，自检失败后 JVM 优雅关停
# 约 300ms 即 exited/ExitCode=1——"拒启"的正确判据是**进程以非零码退出**，
# 不是"inspect 瞬间非 running"（检出失败日志→立即 inspect 的窗口内会采样到 running，竞态）。
dp02_exited() { [ "$(docker inspect dp02-control -f '{{.State.Status}}' 2>/dev/null)" = "exited" ]; }
if wait_for "dp02-control 进程退出（终态）" 30 dp02_exited; then
    DP02_EXIT=$(docker inspect dp02-control -f '{{.State.ExitCode}}')
    if [ "$DP02_EXIT" != "0" ]; then
        ok "注入写凭证的 control 被拒启（State=exited ExitCode=$DP02_EXIT）"
    else
        bad "dp02-control 退出码为 0，自检门失效"
    fi
else
    DP02_STATE=$(docker inspect dp02-control -f '{{.State.Status}}' 2>/dev/null)
    bad "dp02-control 30s 未进入终态（State=$DP02_STATE），自检门失效"
fi
docker rm -f dp02-control > /dev/null 2>&1
echo "  恢复：dp02-control 一次性注入容器已删除（正装 control 未受影响）" | tee -a "$SUMMARY"

# ---------------------------------------------------------------- DP-03
echo "== DP-03 授予 control_app UPDATE outbox → 拒绝启动（AFT-06 动态） ==" | tee -a "$SUMMARY"
psql "grant update on outbox_command to control_app" | tee "$EVIDENCE/dp03-grant.txt"
DP03_SINCE=$(date -u +%Y-%m-%dT%H:%M:%S)
sleep 1
docker compose restart control-app > /dev/null 2>&1
wait_for "control 自检失败日志（UPDATE 权）" 120 dp03_failed || true
docker compose logs --no-color --since "$DP03_SINCE" control-app > "$EVIDENCE/dp03-control.log" 2>&1
assert_contains "日志含『启动自检失败』" "$EVIDENCE/dp03-control.log" "启动自检失败"
assert_contains "日志点名 outbox_command UPDATE 权限" "$EVIDENCE/dp03-control.log" "持有 UPDATE 权限"
# 恢复：revoke + 重启，回到 401 存活
psql "revoke update on outbox_command from control_app" | tee -a "$EVIDENCE/dp03-grant.txt"
docker compose restart control-app > /dev/null 2>&1
wait_for "control 恢复后 401" 150 control_alive || true
assert_eq "revoke 后 control 恢复 401" "$(control_http_code)" "401"
docker compose logs --no-color control-app 2>/dev/null | grep "启动自检通过" | tail -1 | tee -a "$SUMMARY"
[ "$(psql "select has_table_privilege('control_app','outbox_command','UPDATE')" | tr -d '[:space:]')" = "f" ] \
    && ok "revoke 已生效（has_table_privilege=f）" || bad "revoke 未生效"

# ---------------------------------------------------------------- DP-04
echo "== DP-04 B16 hardening inspect 清单 ==" | tee -a "$SUMMARY"
DP04="$EVIDENCE/dp04-checklist.txt"
: > "$DP04"
for svc in control-app publisher-app; do
    cid=$(docker compose ps -q "$svc")
    docker inspect "$cid" > "$EVIDENCE/dp04-$svc.json"
    user=$(docker inspect "$cid" -f '{{.Config.User}}')
    rofs=$(docker inspect "$cid" -f '{{.HostConfig.ReadonlyRootfs}}')
    caps=$(docker inspect "$cid" -f '{{.HostConfig.CapDrop}}')
    nnp=$(docker inspect "$cid" -f '{{.HostConfig.SecurityOpt}}')
    rst=$(docker inspect "$cid" -f '{{.HostConfig.RestartPolicy.Name}}')
    mounts=$(docker inspect "$cid" -f '{{json .Mounts}}')
    {
        echo "-- $svc ($cid)"
        echo "   User=$user  ReadonlyRootfs=$rofs  CapDrop=$caps  Restart=$rst"
        echo "   SecurityOpt=$nnp"
    } | tee -a "$DP04"
    { [ -n "$user" ] && [ "$user" != "root" ] && [ "$user" != "0" ]; } \
        && ok "$svc 非 root（User=$user）" || bad "$svc User=[$user]"
    assert_eq "$svc ReadonlyRootfs" "$rofs" "true"
    case "$caps" in *ALL*) ok "$svc CapDrop 含 ALL";; *) bad "$svc CapDrop=$caps";; esac
    case "$nnp" in *no-new-privileges*) ok "$svc no-new-privileges";; *) bad "$svc SecurityOpt=$nnp";; esac
    echo "$mounts" | grep -q "docker.sock" && bad "$svc 挂了 docker.sock" || ok "$svc 无 docker.sock 挂载"
    assert_eq "$svc restart policy" "$rst" "unless-stopped"
done
# 私钥只读挂载：publisher 有且 RW=false；control 完全没有该挂载
pid=$(docker compose ps -q publisher-app)
KEY_RW=$(jq -r '.[0].Mounts[] | select(.Destination=="/run/secrets/github-app-key.pem") | .RW' "$EVIDENCE/dp04-publisher-app.json")
assert_eq "publisher 私钥挂载 RW（false=只读）" "$KEY_RW" "false"
cid=$(docker compose ps -q control-app)
docker inspect "$cid" -f '{{json .Mounts}}' | grep -q "run/secrets" \
    && bad "control 出现了 secrets 挂载" || ok "control 无私钥挂载（I2 容器无挂载门）"
# 端口暴露面（compose ps 的 Ports 列：发布端口带 "->"，仅 EXPOSE 不带；断言无 "->"）
docker compose ps --format '{{.Service}} {{.Ports}}' | tee -a "$DP04"
PUBPORTS=$(docker compose ps --format '{{.Ports}}' publisher-app)
echo "$PUBPORTS" | grep -q -- '->' && bad "publisher 发布了端口：$PUBPORTS" || ok "publisher 无发布端口"
PGPORTS=$(docker compose ps --format '{{.Ports}}' postgres)
echo "$PGPORTS" | grep -q -- '->' && bad "postgres 发布了端口：$PGPORTS" || ok "postgres 无发布端口（$PGPORTS 仅为镜像 EXPOSE）"
docker compose ps --format '{{.Ports}}' control-app | grep -q "127.0.0.1" \
    && ok "control 端口仅绑 127.0.0.1" || bad "control 端口未绑 loopback"

# ---------------------------------------------------------------- DP-05
# GitHub API 永远走 github-stub（M0 无真实凭证）；模型链路取决于 .env 的
# OPENAI_COMPAT_BASE_URL：指 github-stub = 固定 1 条 finding；指百炼 = 真实模型调用
#（finding 数不定，outbox/发布链路不断言 finding 数）。
echo "== DP-05 端到端冒烟（stub GitHub + 模型链路随 .env，当前=${OPENAI_COMPAT_BASE_URL:-未设}） ==" | tee -a "$SUMMARY"
STUB="http://127.0.0.1:${STUB_ADMIN_PORT:-19090}"
# journal 清零用 DELETE（WireMock 3.x；旧 POST /__admin/requests/reset 已 404，INC-23）
curl -s -X DELETE "$STUB/__admin/requests" > /dev/null # stub 请求日志清零 = 计数基线
# outbox 查询一律按本批次 PR 号圈定：aggregate_sequence 是每 PR 独立编号，
# 全局 max() 基线对新 subject 恒失败（第三轮门禁教训）

HEAD_SHA="deadbeef$(printf 'c%.0s' {1..32})"
BASE_SHA="cafe0000$(printf 'b%.0s' {1..32})"
# PR 号每次随机：固定 7 会在重跑时命中同 revision 去重（不重审已评审快照是正确行为），
# 导致本脚本自身断言失败；100~599 与 DP-14 的 600~899 区间错开。
PR05_NUM=$((100 + RANDOM % 500))
DELIVERY="dp05-$(date +%s)-$PR05_NUM"
cat > "$EVIDENCE/dp05-payload.json" <<JSON
{"action":"opened","number":$PR05_NUM,
 "installation":{"id":${GITHUB_INSTALLATION_ID:-555000}},
 "repository":{"id":9001,"full_name":"stuborg/stubrepo"},
 "pull_request":{"state":"open","draft":false,"merged":false,
   "head":{"sha":"$HEAD_SHA"},"base":{"ref":"main","sha":"$BASE_SHA"}}}
JSON
SIG=$(openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" "$EVIDENCE/dp05-payload.json" | sed 's/^.* //')
HTTP=$(curl -s -o "$EVIDENCE/dp05-response.json" -w '%{http_code}' \
    -X POST http://127.0.0.1:"${CONTROL_PORT:-8080}"/webhooks/github \
    -H "X-Hub-Signature-256: sha256=$SIG" \
    -H "X-GitHub-Event: pull_request" \
    -H "X-GitHub-Delivery: $DELIVERY" \
    -H "Content-Type: application/json" \
    --data-binary @"$EVIDENCE/dp05-payload.json")
assert_eq "webhook pull_request.opened 受理" "$HTTP" "202"
cat "$EVIDENCE/dp05-response.json"; echo

wait_for "outbox 本批次两条全 CONFIRMED" 180 dp05_confirmed || true
psql "select oc.operation_id, oc.command_type, oc.state, oc.aggregate_sequence from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR05_NUM order by oc.aggregate_sequence" \
    | tee "$EVIDENCE/dp05-outbox.txt"
CONFIRMED=$(psql "select count(*) from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR05_NUM and oc.state='CONFIRMED'" | tr -d '[:space:]')
assert_eq "outbox 本批次 CONFIRMED 数" "$CONFIRMED" "2"
echo "  review_finding 总数=$(psql 'select count(*) from review_finding' | tr -d '[:space:]')（模型 stub 模式固定回 1 条；真实模型由百炼决定，0 也正常）" | tee -a "$SUMMARY"

CHECK_OP=$(psql "select oc.operation_id from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR05_NUM and oc.command_type='CREATE_CHECK'" | tr -d '[:space:]')
REVIEW_OP=$(psql "select oc.operation_id from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR05_NUM and oc.command_type='PUBLISH_REVIEW'" | tr -d '[:space:]')
# stub 侧计数断言（恰好一次 = effectively-once 的远端证据）
curl -s -X POST "$STUB/__admin/requests/find" -H 'Content-Type: application/json' \
    -d '{"method":"POST","url":"/repos/stuborg/stubrepo/check-runs"}' > "$EVIDENCE/dp05-stub-checks.json"
curl -s -X POST "$STUB/__admin/requests/find" -H 'Content-Type: application/json' \
    -d "{\"method\":\"POST\",\"url\":\"/repos/stuborg/stubrepo/pulls/$PR05_NUM/reviews\"}" > "$EVIDENCE/dp05-stub-reviews.json"
assert_eq "stub 收到 check-runs POST 恰好 1 次" "$(jq '.requests | length' "$EVIDENCE/dp05-stub-checks.json")" "1"
assert_eq "stub 收到 reviews POST 恰好 1 次" "$(jq '.requests | length' "$EVIDENCE/dp05-stub-reviews.json")" "1"
jq -r '.requests[0].body' "$EVIDENCE/dp05-stub-checks.json" | grep -q "\"external_id\":\"$CHECK_OP\"" \
    && ok "check external_id=operation_id（$CHECK_OP）" || bad "check external_id 不符（期望 $CHECK_OP）"
jq -r '.requests[0].body' "$EVIDENCE/dp05-stub-reviews.json" | grep -qF "<!-- ai-review:$REVIEW_OP -->" \
    && ok "review 含幂等 marker（$REVIEW_OP）" || bad "review 缺 marker（期望 $REVIEW_OP）"
# 链路时序摘录（事件受理 → outbox CONFIRMED 的日志线）
docker compose logs --no-color --since 5m control-app publisher-app 2>/dev/null \
    | grep -iE "intake|snapshot|review|worker|claim|confirm|outbox|publish|快照|评审|账本" \
    | tail -60 > "$EVIDENCE/dp05-timeline.log" || true

# ---------------------------------------------------------------- DP-11
# V3 权限矩阵在 195 实库实测（M1-T01 授权面；对应 CT-19/CT-20 的部署态复核）
echo "== DP-11 V3 权限矩阵实库断言 ==" | tee -a "$SUMMARY"
tp() { psql "select has_table_privilege('$1','$2','$3')" | tr -d '[:space:]'; }
cp_() { psql "select has_column_privilege('$1','$2','$3','$4')" | tr -d '[:space:]'; }
assert_eq "control 对 webhook_inbox SELECT"   "$(tp control_app webhook_inbox SELECT)" "t"
assert_eq "control 对 webhook_inbox INSERT"   "$(tp control_app webhook_inbox INSERT)" "t"
assert_eq "control 对 webhook_inbox UPDATE"   "$(tp control_app webhook_inbox UPDATE)" "t"
assert_eq "control 对 webhook_inbox DELETE 无" "$(tp control_app webhook_inbox DELETE)" "f"
for p in SELECT INSERT UPDATE DELETE; do
    assert_eq "publisher 对 webhook_inbox $p 零权限（CT-19）" "$(tp publisher_app webhook_inbox $p)" "f"
done
assert_eq "publisher 对 publication_resource 表级 UPDATE 无" "$(tp publisher_app publication_resource UPDATE)" "f"
assert_eq "publisher 可更新观测列 state"          "$(cp_ publisher_app publication_resource state UPDATE)" "t"
assert_eq "publisher 可更新观测列 last_checked_at" "$(cp_ publisher_app publication_resource last_checked_at UPDATE)" "t"
assert_eq "publisher 可更新观测列 next_check_at"   "$(cp_ publisher_app publication_resource next_check_at UPDATE)" "t"
assert_eq "publisher 可更新观测列 check_error_count" "$(cp_ publisher_app publication_resource check_error_count UPDATE)" "t"
assert_eq "publisher 不可更新非观测列 remote_id（CT-20）" "$(cp_ publisher_app publication_resource remote_id UPDATE)" "f"
assert_eq "publisher 不可更新非观测列 marker"        "$(cp_ publisher_app publication_resource marker UPDATE)" "f"
assert_eq "control 对 publication_resource SELECT"      "$(tp control_app publication_resource SELECT)" "t"
assert_eq "control 对 publication_resource UPDATE 无"   "$(tp control_app publication_resource UPDATE)" "f"
assert_eq "publisher 对 outbox_command SELECT"  "$(tp publisher_app outbox_command SELECT)" "t"
assert_eq "publisher 对 outbox_command UPDATE"  "$(tp publisher_app outbox_command UPDATE)" "t"
assert_eq "publisher 对 outbox_command INSERT 无" "$(tp publisher_app outbox_command INSERT)" "f"
assert_eq "publisher 对 outbox_command DELETE 无" "$(tp publisher_app outbox_command DELETE)" "f"
assert_eq "control 对 outbox_command UPDATE 无（AFT-06）" "$(tp control_app outbox_command UPDATE)" "f"

# ---------------------------------------------------------------- DP-12
# 三 worker 心跳（启动标记）+ 容器重启自动恢复
echo "== DP-12 三 worker 心跳与重启自愈 ==" | tee -a "$SUMMARY"
docker compose logs --no-color control-app   > "$EVIDENCE/dp12-control-before.log" 2>&1
docker compose logs --no-color publisher-app > "$EVIDENCE/dp12-publisher-before.log" 2>&1
assert_contains "InboxProcessor 已启动"      "$EVIDENCE/dp12-control-before.log" "InboxProcessor 启动"
assert_contains "PrStateReconciler 已启动"   "$EVIDENCE/dp12-control-before.log" "PrStateReconciler 启动"
assert_contains "DriftReconciler 已启动"     "$EVIDENCE/dp12-publisher-before.log" "DriftReconciler 启动"

docker compose restart control-app > /dev/null 2>&1
wait_for "control 重启后 401 存活" 150 control_alive || true
assert_eq "control 重启后 401" "$(control_http_code)" "401"
docker compose logs --no-color control-app > "$EVIDENCE/dp12-control-after.log" 2>&1
INBOX_BOOTS=$(grep -c "InboxProcessor 启动" "$EVIDENCE/dp12-control-after.log")
RECON_BOOTS=$(grep -c "PrStateReconciler 启动" "$EVIDENCE/dp12-control-after.log")
[ "$INBOX_BOOTS" -ge 2 ] && ok "InboxProcessor 重启后再启动（${INBOX_BOOTS} 次标记）" || bad "InboxProcessor 重启后未再启动（${INBOX_BOOTS}）"
[ "$RECON_BOOTS" -ge 2 ] && ok "PrStateReconciler 重启后再启动（${RECON_BOOTS} 次标记）" || bad "PrStateReconciler 重启后未再启动（${RECON_BOOTS}）"

docker compose restart publisher-app > /dev/null 2>&1
# 计数式等待：publisher_started() 匹配全量日志会被首次启动的旧标记骗过（INC-23 同批修复）
dp12_drift_restarted() { [ "$(docker compose logs --no-color publisher-app 2>/dev/null | grep -c 'DriftReconciler 启动')" -ge "$DRIFT_BEFORE" ]; }
DRIFT_BEFORE=$(docker compose logs --no-color publisher-app 2>/dev/null | grep -c 'DriftReconciler 启动')
DRIFT_BEFORE=$((DRIFT_BEFORE + 1))
wait_for "DriftReconciler 重启后再启动" 150 dp12_drift_restarted || true
docker compose logs --no-color publisher-app > "$EVIDENCE/dp12-publisher-after.log" 2>&1
DRIFT_BOOTS=$(grep -c "DriftReconciler 启动" "$EVIDENCE/dp12-publisher-after.log")
[ "$DRIFT_BOOTS" -ge "$DRIFT_BEFORE" ] && ok "DriftReconciler 重启后再启动（${DRIFT_BOOTS} 次标记）" || bad "DriftReconciler 重启后未再启动（${DRIFT_BOOTS}）"

# ---------------------------------------------------------------- DP-14
# 杀 control 模拟半截处理 → 重启重放恰好一次（账本核对）
# 前提：stub 模式（GITHUB_API_BASE 指 github-stub）。webhook 202 时 inbox 行
# 已同步落库（RECEIVED），立即 SIGKILL 即命中崩溃窗口 1（已受理未处理）；
# T1 中/T1 后两个窗口由 ST-17 IT 在 195 覆盖，此处只验证端到端恰好一次。
echo "== DP-14 半截处理崩溃 → 重放恰好一次 ==" | tee -a "$SUMMARY"
# journal 清零：WireMock 3.x 清账本是 DELETE /__admin/requests
# （旧 POST /__admin/requests/reset 已 404，INC-23）
curl -s -X DELETE "$STUB/__admin/requests" > /dev/null
# PR 号每次随机：重跑本脚本不受上一轮残留数据影响（600~899 与 DP-05 的 100~599 错开）
PR14_NUM=$((600 + RANDOM % 300))
DELIVERY14="dp14-$(date +%s)-$PR14_NUM"
cat > "$EVIDENCE/dp14-payload.json" <<JSON
{"action":"opened","number":$PR14_NUM,
 "installation":{"id":${GITHUB_INSTALLATION_ID:-555000}},
 "repository":{"id":9001,"full_name":"stuborg/stubrepo"},
 "pull_request":{"state":"open","draft":false,"merged":false,
   "head":{"sha":"$HEAD_SHA"},"base":{"ref":"main","sha":"$BASE_SHA"}}}
JSON
SIG14=$(openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" "$EVIDENCE/dp14-payload.json" | sed 's/^.* //')
HTTP14=$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST http://127.0.0.1:"${CONTROL_PORT:-8080}"/webhooks/github \
    -H "X-Hub-Signature-256: sha256=$SIG14" \
    -H "X-GitHub-Event: pull_request" \
    -H "X-GitHub-Delivery: $DELIVERY14" \
    -H "Content-Type: application/json" \
    --data-binary @"$EVIDENCE/dp14-payload.json")
assert_eq "DP-14 webhook 受理" "$HTTP14" "202"

# 202 返回时 inbox 行已同步落库（RECEIVED）。立即 SIGKILL = 崩溃窗口 1
#（已受理、InboxProcessor 尚未领取）；T1 中/T1 后两窗口由 ST-17 IT 覆盖。
docker compose kill -s SIGKILL control-app > /dev/null 2>&1
sleep 2
CID14=$(docker compose ps -q control-app)
ST14=$(docker inspect "$CID14" -f '{{.State.Status}}' 2>/dev/null || echo missing)
[ "$ST14" != "running" ] && ok "control 已 SIGKILL（State=$ST14）" || bad "control 仍在运行（$ST14）"
psql "select delivery_id, state, attempt_count from webhook_inbox where delivery_id='$DELIVERY14'" | tee "$EVIDENCE/dp14-inbox-before-kill.txt"

docker compose up -d control-app > /dev/null 2>&1
wait_for "control 复活后 401" 150 control_alive || true
dp14_processed()  { [ "$(psql "select state from webhook_inbox where delivery_id='$DELIVERY14'" | tr -d '[:space:]')" = "PROCESSED" ]; }
wait_for "inbox 行重放至 PROCESSED" 600 dp14_processed || true
psql "select delivery_id, state, attempt_count from webhook_inbox where delivery_id='$DELIVERY14'" | tee "$EVIDENCE/dp14-inbox-after.txt"
assert_eq "inbox 行最终 PROCESSED" "$(psql "select state from webhook_inbox where delivery_id='$DELIVERY14'" | tr -d '[:space:]')" "PROCESSED"
assert_eq "该 delivery 恰好 1 行（无重复登记）" "$(psql "select count(*) from webhook_inbox where delivery_id='$DELIVERY14'" | tr -d '[:space:]')" "1"
# inbox PROCESSED 只代表 T1 完成，评审流水线（模型调用+发布）仍在异步推进，
# outbox 断言前必须等收敛（INC-23 同批教训：无等待的即时断言是假性 FAIL）
dp14_confirmed() { [ "$(psql "select count(*) from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR14_NUM and oc.state='CONFIRMED'" | tr -d '[:space:]')" = "2" ]; }
wait_for "PR#$PR14_NUM outbox 两条 CONFIRMED" 300 dp14_confirmed || true
RUNS14=$(psql "select count(*) from review_run rr join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR14_NUM" | tr -d '[:space:]')
assert_eq "PR#$PR14_NUM 恰好 1 个 ReviewRun（重放不建重复 Run）" "$RUNS14" "1"
CONF14=$(psql "select count(*) from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='stuborg/stubrepo' and s.pr_number=$PR14_NUM and oc.state='CONFIRMED'" | tr -d '[:space:]')
assert_eq "PR#$PR14_NUM outbox CONFIRMED=2（check+review）" "$CONF14" "2"
curl -s -X POST "$STUB/__admin/requests/find" -H 'Content-Type: application/json' \
    -d '{"method":"POST","url":"/repos/stuborg/stubrepo/check-runs"}' > "$EVIDENCE/dp14-stub-checks.json"
curl -s -X POST "$STUB/__admin/requests/find" -H 'Content-Type: application/json' \
    -d "{\"method\":\"POST\",\"url\":\"/repos/stuborg/stubrepo/pulls/$PR14_NUM/reviews\"}" > "$EVIDENCE/dp14-stub-reviews.json"
assert_eq "stub 收到 check-runs POST 恰好 1 次（崩溃重放不双发）" "$(jq '.requests | length' "$EVIDENCE/dp14-stub-checks.json")" "1"
assert_eq "stub 收到 PR#$PR14_NUM reviews POST 恰好 1 次" "$(jq '.requests | length' "$EVIDENCE/dp14-stub-reviews.json")" "1"

# ---------------------------------------------------------------- 汇总
echo "==================================================" | tee -a "$SUMMARY"
echo "结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE" | tee -a "$SUMMARY"
[ "$FAIL" -eq 0 ]
