#!/usr/bin/env bash
# ============================================================================
# DP-01~05 部署验证（M0-T18，docs/M0-技术方案.md §12 L5）
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
dp05_confirmed() { [ "$(psql "select count(*) from outbox_command where aggregate_sequence > $BASE_SEQ and state='CONFIRMED'" | tr -d '[:space:]')" = "2" ]; }

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
assert_contains "migrate 执行了 V1/V2 迁移" "$EVIDENCE/dp01-migrate.log" "Migrating schema"

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
DP02_STATE=$(docker inspect dp02-control -f '{{.State.Status}}')
DP02_RESTARTS=$(docker inspect dp02-control -f '{{.RestartCount}}')
# compose run 继承 unless-stopped：自检失败 → 崩溃 → 循环重启，inspect 瞬间可能
# 恰好 running。"拒绝进入稳定服务"的判据 = 非 running，或 RestartCount>0（崩过）
if [ "$DP02_STATE" != "running" ] || [ "$DP02_RESTARTS" -gt 0 ]; then
    ok "注入写凭证的 control 未进入稳定服务（State=$DP02_STATE RestartCount=$DP02_RESTARTS）"
else
    bad "dp02-control 稳定运行中（State=running RestartCount=0），自检门失效"
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
curl -s -X POST "$STUB/__admin/requests/reset" > /dev/null # stub 请求日志清零 = 计数基线
BASE_SEQ=$(psql "select coalesce(max(aggregate_sequence),0) from outbox_command" | tr -d '[:space:]')
echo "  outbox 基线 aggregate_sequence=$BASE_SEQ" | tee -a "$SUMMARY"

HEAD_SHA="deadbeef$(printf 'c%.0s' {1..32})"
BASE_SHA="cafe0000$(printf 'b%.0s' {1..32})"
DELIVERY="dp05-$(date +%s)"
cat > "$EVIDENCE/dp05-payload.json" <<JSON
{"action":"opened","number":7,
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
psql "select operation_id, command_type, state, aggregate_sequence from outbox_command where aggregate_sequence > $BASE_SEQ order by aggregate_sequence" \
    | tee "$EVIDENCE/dp05-outbox.txt"
CONFIRMED=$(psql "select count(*) from outbox_command where aggregate_sequence > $BASE_SEQ and state='CONFIRMED'" | tr -d '[:space:]')
assert_eq "outbox 本批次 CONFIRMED 数" "$CONFIRMED" "2"
echo "  review_finding 总数=$(psql 'select count(*) from review_finding' | tr -d '[:space:]')（模型 stub 模式固定回 1 条；真实模型由百炼决定，0 也正常）" | tee -a "$SUMMARY"

CHECK_OP=$(psql "select operation_id from outbox_command where aggregate_sequence > $BASE_SEQ and command_type='CREATE_CHECK'" | tr -d '[:space:]')
REVIEW_OP=$(psql "select operation_id from outbox_command where aggregate_sequence > $BASE_SEQ and command_type='PUBLISH_REVIEW'" | tr -d '[:space:]')
# stub 侧计数断言（恰好一次 = effectively-once 的远端证据）
curl -s -X POST "$STUB/__admin/requests/find" -H 'Content-Type: application/json' \
    -d '{"method":"POST","url":"/repos/stuborg/stubrepo/check-runs"}' > "$EVIDENCE/dp05-stub-checks.json"
curl -s -X POST "$STUB/__admin/requests/find" -H 'Content-Type: application/json' \
    -d '{"method":"POST","url":"/repos/stuborg/stubrepo/pulls/7/reviews"}' > "$EVIDENCE/dp05-stub-reviews.json"
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

# ---------------------------------------------------------------- 汇总
echo "==================================================" | tee -a "$SUMMARY"
echo "结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE" | tee -a "$SUMMARY"
[ "$FAIL" -eq 0 ]
