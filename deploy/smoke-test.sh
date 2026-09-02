#!/usr/bin/env bash
# ============================================================================
# DP-01~05 部署验证（M0-T18，docs/M0-技术方案.md §12 L5）
# DP-11~14 部署验证（M1-T09，docs/M1-技术方案.md §11）：
#   DP-11 V3 权限矩阵实库断言；DP-12 三 worker 心跳+重启自愈；
#   DP-14 杀 control 半截处理→重放恰好一次。DP-13（真实仓库 draft 闭环）
#   需真实模式单独执行，见 deploy/dp13-real-draft.sh。
# DP-15~19 部署门（M2，docs/M2-技术方案.md §11 部署门表）：
#   DP-15 V4 权限矩阵栈级断言；DP-16 双自检+401+M0/M1 回归门（回归=本脚本
#   前半段 DP-01~05/11~14 的全量执行结果）；DP-17 SIGKILL 后续跑模型计数恰 1；
#   DP-18 drift 修复闭环栈级门禁；DP-19 带 V3 种子数据原地升 V4（临时库）。
# DP-20~28 部署门（M3，docs/M3-技术方案.md §11 部署门表）：
#   DP-20 V5 权限矩阵（model_call_ledger 位面）；DP-21 启动自检矩阵（负例拒启）；
#   DP-22 账本冒烟（两段记账恰好一条，stub 模型模式限定，否则 SKIP）；
#   DP-23 V5 全约束种子（非法逐条拒绝，超库身份）；DP-24 V5 权限行为面（SET ROLE）；
#   DP-25 主备配置矩阵（C-2 继承/独立/防伪）；DP-26 密钥挂载零回显；
#   DP-27 Gateway 冒烟（账本↔checkpoint↔发布一致，stub 模型模式限定）；
#   DP-28 熔断/时限旋钮模式感知（混合=默认值零声明，演练窗=三元组成对）。模型端点故障注入面在 m3-lib.sh（m3_model_fault_on/off）。
# DP-05/DP-14 依赖 stub 模式（GITHUB_API_BASE 指 github-stub）与
# wiremock 固定 PR 元数据映射（head/base SHA 与本脚本负载一致）。
# M2 起新增：CONFIRMED 后立即经 m2_register_pr_resources 注入"探针可见"运行时
# 映射——静态 stub 探针恒回空列表，不注入会被 DriftReconciler 当 MISSING 铸
# repair 单，污染本批次断言（见 m2-lib.sh 文件头"关键设计"）。
# DP-17 需 stub 模型模式（OPENAI_COMPAT_BASE_URL 指 github-stub）才能经 stub
# journal 数模型调用；真实模型模式下该节记 [SKIP] 不计成败（见 README M2 节）。
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

# M2 共享库（DP-15~19 用；m2_ 前缀函数，见 m2-lib.sh 文件头）
M2_EVIDENCE="$EVIDENCE"
. ./m2-lib.sh
trap m2_cleanup EXIT
m2_probe_sync_start   # TB-13：stub 探针联动守护

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
# M2：CONFIRMED 后立即注入"探针可见"映射，防 DriftReconciler 把静态 stub 的
# 空探针当 MISSING 铸 repair 单污染后续断言（m2-lib.sh 文件头）
m2_register_pr_resources "$PR05_NUM" || true
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
# M2：同 DP-05，CONFIRMED 后注入探针可见映射防 drift 搅局
m2_register_pr_resources "$PR14_NUM" || true
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

REGRESSION_FAILS=$FAIL   # DP-01~05/11~14 的 FAIL 计数 = M0/M1 回归结果（DP-16 门禁依据）

# ---------------------------------------------------------------- DP-15
# V4 权限矩阵栈级断言（波次1主代码事实的部署面复验：publisher 对 repair_request
# 只有 SELECT + 列级 INSERT/UPDATE；trigger 强制初始 PENDING+审批三列空；
# publisher 零 outbox INSERT；step_checkpoint 表 control 专属）
echo "== DP-15 V4 权限矩阵栈级断言 ==" | tee -a "$SUMMARY"
# -- 权限位面（与 DP-11 同风格；tp/cp_ 助手在 DP-11 节定义）
assert_eq "publisher 对 repair_request SELECT"   "$(tp publisher_app repair_request SELECT)" "t"
assert_eq "publisher 对 repair_request 表级 INSERT 无（V4 为列级授权，不提升表级 ACL——TB-11 裁定：设计意图即列级，表级恒 f 才是正确形态）" "$(tp publisher_app repair_request INSERT)" "f"
assert_eq "publisher 对 repair_request 表级 UPDATE 无（同上）" "$(tp publisher_app repair_request UPDATE)" "f"
assert_eq "publisher 对 repair_request DELETE 无" "$(tp publisher_app repair_request DELETE)" "f"
assert_eq "control 对 repair_request SELECT"  "$(tp control_app repair_request SELECT)" "t"
assert_eq "control 对 repair_request UPDATE（含审批列）"  "$(tp control_app repair_request UPDATE)" "t"
assert_eq "control 对 repair_request INSERT 无（铸单只在 publisher）" "$(tp control_app repair_request INSERT)" "f"
assert_eq "publisher 对 step_checkpoint SELECT 无（AFT-18）" "$(tp publisher_app step_checkpoint SELECT)" "f"
for p in SELECT INSERT UPDATE DELETE; do
    assert_eq "control 对 step_checkpoint $p（control 专属）" "$(tp control_app step_checkpoint $p)" "t"
done
assert_eq "publisher 对 outbox_command INSERT 无（V4 重申，回归）" "$(tp publisher_app outbox_command INSERT)" "f"
# -- 列级授权面
assert_eq "publisher 可 INSERT 列 id"            "$(cp_ publisher_app repair_request id INSERT)" "t"
assert_eq "publisher 可 INSERT 列 policy_tier"   "$(cp_ publisher_app repair_request policy_tier INSERT)" "t"
assert_eq "publisher 不可 INSERT 列 approved_by"    "$(cp_ publisher_app repair_request approved_by INSERT)" "f"
assert_eq "publisher 不可 INSERT 列 repair_run_id"  "$(cp_ publisher_app repair_request repair_run_id INSERT)" "f"
assert_eq "publisher 可 UPDATE 列 state"               "$(cp_ publisher_app repair_request state UPDATE)" "t"
assert_eq "publisher 可 UPDATE 列 repair_operation_id" "$(cp_ publisher_app repair_request repair_operation_id UPDATE)" "t"
assert_eq "publisher 不可 UPDATE 列 policy_tier（I21）"        "$(cp_ publisher_app repair_request policy_tier UPDATE)" "f"
assert_eq "publisher 不可 UPDATE 列 approved_by"              "$(cp_ publisher_app repair_request approved_by UPDATE)" "f"
assert_eq "publisher 不可 UPDATE 列 publication_resource_id"  "$(cp_ publisher_app repair_request publication_resource_id UPDATE)" "f"
# -- 行为面（SET ROLE publisher_app 实库尝试；事务内验证后 rollback，零残留）
RID15=$(psql "select id from publication_resource order by created_at desc limit 1" | tr -d '[:space:]')
if [ -z "$RID15" ]; then
    bad "DP-15 需要库内已有 publication_resource 行作 FK（正常由 DP-05/DP-14 产生；请全脚本顺序执行）"
else
    U15=$(psql "select gen_random_uuid()" | tr -d '[:space:]')
    # 1) 整行 INSERT（夹带未授权列 approved_by）→ 应被 ACL 拒
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; insert into repair_request (id, publication_resource_id, resource_type, policy_tier, state, approved_by) values ('$U15','$RID15','CHECK_RUN','AUTO','PENDING','hacker')" 2>&1)
    echo "$out" | grep -q "permission denied" \
        && ok "publisher 整行 INSERT repair_request 被拒（permission denied）" \
        || bad "publisher 整行 INSERT 未被拒：$out"
    # 2) 列级 INSERT 合法列 → 应过且 state=PENDING、审批三列空（trigger 钉死）
    #    TB-11：多语句 -c 必然回显 BEGIN/SET/INSERT 0 1/ROLLBACK 命令标签，
    #    断言改全行匹配结果行（grep -qx），不与命令标签逐字全等
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "begin; set local role publisher_app; insert into repair_request (id, publication_resource_id, resource_type, policy_tier, state) values ('$U15','$RID15','CHECK_RUN','AUTO','PENDING'); select state||'|'||coalesce(approved_by,'<null>')||'|'||coalesce(approved_at::text,'<null>') from repair_request where id='$U15'; rollback" 2>&1)
    echo "$out" | grep -qx "PENDING|<null>|<null>" \
        && ok "publisher 列级 INSERT 合法列通过，state=PENDING 且审批三列空" \
        || bad "publisher 列级 INSERT 异常：$out"
    # 3) 合法列但 state=DISPATCHED → trigger 拒（只能以 PENDING 铸造）
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; insert into repair_request (id, publication_resource_id, resource_type, policy_tier, state) values ('$U15','$RID15','CHECK_RUN','AUTO','DISPATCHED')" 2>&1)
    echo "$out" | grep -q "只能以 PENDING" \
        && ok "非 PENDING 铸造被 trigger 拒（trg_repair_insert_pending）" \
        || bad "非 PENDING 铸造未被 trigger 拒：$out"
    # 3b) 超级用户 INSERT 夹带审批列 → 同 trigger 拒（隔离 ACL 面，纯验证 trigger）
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "insert into repair_request (id, publication_resource_id, resource_type, policy_tier, state, approved_by, approved_at, approval_reason) values ('$U15','$RID15','CHECK_RUN','AUTO','PENDING','x',now(),'y')" 2>&1)
    echo "$out" | grep -q "只能以 PENDING" \
        && ok "审批三列非空铸造被 trigger 拒（超级用户同拒）" \
        || bad "审批三列铸造未被 trigger 拒：$out"
    # 4) publisher UPDATE 审批列 → ACL 拒
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; update repair_request set approved_by='x' where id='$U15'" 2>&1)
    echo "$out" | grep -q "permission denied" \
        && ok "publisher 写 approved_by 被拒（列级 ACL）" \
        || bad "publisher 写 approved_by 未被拒：$out"
    # 4b) publisher UPDATE policy_tier → ACL 拒（I21 档级不可改）
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; update repair_request set policy_tier='MANUAL' where id='$U15'" 2>&1)
    echo "$out" | grep -q "permission denied" \
        && ok "publisher 改 policy_tier 被拒（列级 ACL）" \
        || bad "publisher 改 policy_tier 未被拒：$out"
    # 4c) policy_tier 不可变 trigger（超级用户也被拒；同事务 insert+update，出错整体回滚零残留）
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "insert into repair_request (id, publication_resource_id, resource_type, policy_tier, state) values ('$U15','$RID15','CHECK_RUN','AUTO','PENDING'); update repair_request set policy_tier='MANUAL' where id='$U15'" 2>&1)
    echo "$out" | grep -q "policy_tier is immutable" \
        && ok "policy_tier 不可变 trigger 生效（trg_repair_tier_immutable）" \
        || bad "policy_tier 不可变 trigger 未生效：$out"
    # 5) publisher INSERT outbox_command → 拒（AFT-14 栈级复验）
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; insert into outbox_command (operation_id) values (gen_random_uuid())" 2>&1)
    echo "$out" | grep -q "permission denied" \
        && ok "publisher INSERT outbox_command 被拒（零 outbox INSERT）" \
        || bad "publisher INSERT outbox_command 未被拒：$out"
    # 6) publisher 读/写 step_checkpoint → 均拒（checkpoint 表 control 专属）
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; select count(*) from step_checkpoint" 2>&1)
    echo "$out" | grep -q "permission denied" \
        && ok "publisher SELECT step_checkpoint 被拒" \
        || bad "publisher SELECT step_checkpoint 未被拒：$out"
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc \
        "set role publisher_app; insert into step_checkpoint (id) values (gen_random_uuid())" 2>&1)
    echo "$out" | grep -q "permission denied" \
        && ok "publisher INSERT step_checkpoint 被拒" \
        || bad "publisher INSERT step_checkpoint 未被拒：$out"
fi

# ---------------------------------------------------------------- DP-16
# 双应用自检 + 401 探针守门 + M0/M1 DP-01~05/11~14 全回归门。
# 回归的"编排调用既有检查"= 本脚本前半段已全量执行的 DP-01~05/11~14
#（它们的 FAIL 计数即回归结果；DoD 要求"回归不是抽样是全量"）。
echo "== DP-16 双应用自检 + 401 守门 + M0/M1 全回归门 ==" | tee -a "$SUMMARY"
assert_eq "control 未签名 POST → 401（守门）" "$(m2_control_http_code)" "401"
docker compose logs --no-color control-app   > "$EVIDENCE/dp16-control.log" 2>&1
docker compose logs --no-color publisher-app > "$EVIDENCE/dp16-publisher.log" 2>&1
assert_contains "control 日志含『启动自检通过』"   "$EVIDENCE/dp16-control.log" "启动自检通过"
assert_contains "publisher 日志含『启动自检通过』" "$EVIDENCE/dp16-publisher.log" "启动自检通过"
docker compose ps --format '{{.Service}}\t{{.Status}}' | tee "$EVIDENCE/dp16-ps.txt"
for svc in postgres control-app publisher-app github-stub; do
    st=$(docker compose ps --format '{{.Status}}' "$svc")
    case "$st" in *running*|*Up*) ok "$svc 存活（$st）";; *) bad "$svc 未存活：$st";; esac
done
if [ "$REGRESSION_FAILS" -eq 0 ]; then
    ok "M0/M1 DP-01~05/11~14 全回归 PASS（本 run 前半段全量执行，FAIL=0）"
else
    bad "M0/M1 回归存在 FAIL（${REGRESSION_FAILS} 条）——M2 门禁不放行"
fi

# ---------------------------------------------------------------- DP-17
# SIGKILL 真实容器后续跑完成，stub journal 模型计数恰 1（I19 checkpoint 门禁化）。
# 崩溃窗口说明（诚实清单）：checkpoint 提交与 T2 之间是进程内毫秒级窗口，栈外
# 无法精确命中；本门轮询 checkpoint 落库后立即 SIGKILL，实际落点为
# "checkpoint 后 / T2 后"两者之一——两窗口期望同为模型计数恰 1，门禁语义等价；
# 精确的"T2 未完"窗口由 ST-27 IT 钉死。
echo "== DP-17 SIGKILL 续跑：模型计数恰 1（checkpoint 门禁化） ==" | tee -a "$SUMMARY"
if ! m2_stub_github_mode || ! m2_stub_model_mode; then
    echo "  [SKIP] DP-17 需全 stub 模式（GITHUB_API_BASE 与 OPENAI_COMPAT_BASE_URL 均指 github-stub）；当前 OPENAI_COMPAT_BASE_URL=${OPENAI_COMPAT_BASE_URL:-未设}" | tee -a "$SUMMARY"
else
    m2_journal_reset
    # 模型延迟放大观察窗口。M3 起必须 < per-call-timeout（全 stub 演练窗三元组
    # percall=20s，INC-61）：旧值 45000 在 M2（percall=120s）可存活，在 M3 窗口
    # 会逐次 TIMEOUT 烧光重试预算
    m2_model_delay_on 12000
    PR17_NUM=$((9000 + RANDOM % 500))
    HTTP17=$(m2_send_pr_webhook "$PR17_NUM" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" dp17)
    assert_eq "DP-17 webhook 受理" "$HTTP17" "202"
    m2_wait_sql "PR#$PR17_NUM checkpoint 落库" 300 "1" \
        "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR17_NUM" || true
    ST17=$(m2_kill_app control-app)
    [ "$ST17" != "running" ] && ok "control 已 SIGKILL（State=$ST17）" || bad "control 仍在运行（$ST17）"
    m2_model_delay_off
    docker compose up -d control-app > /dev/null 2>&1
    wait_for "control 复活后 401" 180 control_alive || true
    # 续跑需等租约过期回收（app.worker.max-lease-seconds 默认 600s；可在 .env 设
    # APP_WORKER_MAXLEASESECONDS=60 加速，见 README M2 节——默认配置下本节约 10 分钟）
    m2_wait_sql "PR#$PR17_NUM outbox 两条 CONFIRMED（续跑收敛）" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR17_NUM") and oc.state='CONFIRMED'" || true
    M17=$(m2_model_calls "$EVIDENCE/dp17-model-calls.json")
    assert_eq "stub journal 模型调用计数恰 1（checkpoint 续跑零重调）" "$M17" "1"
    assert_eq "PR#$PR17_NUM checkpoint 恰 1 行（无重复登记）" "$(m2_pr_checkpoint_count "$PR17_NUM")" "1"
    assert_eq "PR#$PR17_NUM 恰好 1 个 ReviewRun（续跑不建重复 Run）" "$(m2_pr_run_count "$PR17_NUM")" "1"
    m2_register_pr_resources "$PR17_NUM" || true
fi

# ---------------------------------------------------------------- DP-18
# drift 修复闭环栈级门禁（E2E-28 的门禁化；I26 新行模型 + I27 REPAIR Run）
echo "== DP-18 stub 删 check-run → 巡检自动修复闭环 ==" | tee -a "$SUMMARY"
if ! m2_stub_github_mode; then
    echo "  [SKIP] DP-18 需 stub GitHub 模式（GITHUB_API_BASE 指 github-stub）" | tee -a "$SUMMARY"
else
    m2_journal_reset
    PR18_NUM=$((9500 + RANDOM % 400))
    if m2_run_pr_e2e "$PR18_NUM" dp18 600; then
        ok "DP-18 基线 PR#$PR18_NUM 闭环 CONFIRMED=2"
    else
        bad "DP-18 基线闭环未收敛（后续断言可能连带失败）"
    fi
    CK18=$(m2_pr_op "$PR18_NUM" CREATE_CHECK CONFIRMED)
    RID18=$(m2_resource_of_op "$CK18")
    [ -n "$RID18" ] || bad "DP-18 找不到 check 资源行（op=$CK18）"
    # TB-12：stub 创建已改随机 id（7200000-7499999），旧行 remote_id 从基线行捕获而非硬编码
    OLD_REMOTE18=$(m2_resource_field "$RID18" remote_id)
    [ -n "$OLD_REMOTE18" ] || bad "DP-18 基线资源行 remote_id 为空（RID18=$RID18）"
    NEWID18=$((7100000 + RANDOM % 50000))
    # 注入：stub 侧删 check-run 对象（摘除探针可见映射，回落静态空列表）；
    # 修复重建将获新远端 id（避让 uq_pub_resource(resource_type,remote_id)，I26
    # 旧行保留原 remote_id 不覆盖，重建若还回旧 id 会 ON CONFLICT DO NOTHING）
    m2_check_present_remove "$CK18"
    m2_post_check_override_on "$NEWID18"
    m2_wait_sql "资源转 MISSING（巡检发现删除）" 240 "MISSING" \
        "select state from publication_resource where id='$RID18'" || true
    m2_wait_sql "repair_request 铸造（一资源一活跃单）" 120 "1" \
        "select count(*) from repair_request where publication_resource_id='$RID18'" || true
    REQ18=$(m2_request_of_resource "$RID18")
    assert_eq "修复单档级 AUTO（CHECK_RUN 状态型）" "$(m2_request_field "$REQ18" policy_tier)" "AUTO"
    m2_wait_sql "修复单 REPAIRED（MISSING→单→命令→新 PRESENT 全链收敛）" 300 "REPAIRED" \
        "select state from repair_request where id='$REQ18'" || true
    assert_eq "旧行 REPAIRED" "$(m2_resource_field "$RID18" state)" "REPAIRED"
    ROP18=$(m2_request_field "$REQ18" repair_operation_id)
    assert_eq "旧行 repaired_by=repair 命令" "$(m2_resource_field "$RID18" repaired_by_operation_id)" "$ROP18"
    assert_eq "旧行原 remote_id 保留不覆盖（I26）" "$(m2_resource_field "$RID18" remote_id)" "$OLD_REMOTE18"
    NEWRID18=$(m2_psql "select id from publication_resource where replaces_resource_id='$RID18' and state='PRESENT'" | tr -d '[:space:]')
    [ -n "$NEWRID18" ] && ok "新 PRESENT 行存在（replaces_resource_id 链回旧行）" || bad "缺新 PRESENT 行"
    assert_eq "新行 remote_id=重建的新远端对象" "$(m2_resource_field "$NEWRID18" remote_id)" "$NEWID18"
    assert_eq "repair 命令 CONFIRMED" "$(m2_psql "select state from outbox_command where operation_id='$ROP18'" | tr -d '[:space:]')" "CONFIRMED"
    assert_eq "REPAIR Run 独立铸造（I27，不挂终态评审 Run）" \
        "$(m2_psql "select run_mode from review_run rr join repair_request q on q.repair_run_id=rr.id where q.id='$REQ18'" | tr -d '[:space:]')" "REPAIR"
    m2_journal_find POST '"url":"/repos/stuborg/stubrepo/check-runs"' "$EVIDENCE/dp18-stub-checks.json"
    assert_eq "stub check-runs POST 恰 2 次（原始创建+修复重建）" "$(m2_journal_count "$EVIDENCE/dp18-stub-checks.json")" "2"
    # 复原：摘除 POST 覆盖映射；新对象注册探针可见（防新一轮 drift 污染后续门）
    m2_post_check_override_off
    [ -n "$ROP18" ] && m2_check_present_add "$ROP18" "$NEWID18"
    echo "  复原：stub 运行时映射已复位（详见 trap m2_cleanup 兜底）" | tee -a "$SUMMARY"
fi

# ---------------------------------------------------------------- DP-19
# 带 V3 历史数据原地升 V4（CT-22 的部署面；评审 #17）。
# 方法：同库 postgres 内开临时库 dp19_upgrade → flyway -target=3 建到 V3 →
# 灌 db/dp19-seed-v3.sql（V3 形态 Run/Outbox/PRESENT/MISSING/inbox/finding/账本）
# → flyway migrate 升 V4 → 断言数据零丢失+旧记录可读+权限不放宽 → 删临时库。
# 主栈本身即"已升 V4"形态，其全量 smoke 绿 = 升级后回归证据（本脚本 DP-01~18）。
echo "== DP-19 带 V3 种子数据原地升 V4（临时库） ==" | tee -a "$SUMMARY"
DP19_DB="dp19_upgrade"
psql "drop database if exists $DP19_DB" > /dev/null 2>&1
psql "create database $DP19_DB" > "$EVIDENCE/dp19.log" 2>&1
docker compose run --rm -T --no-deps -e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$DP19_DB" \
    migrate -target=3 -connectRetries=30 migrate >> "$EVIDENCE/dp19.log" 2>&1
assert_eq "临时库 flyway 停点 V3" "$(m2_psql_db "$DP19_DB" "select max(version::int) from flyway_schema_history where success" | tr -d '[:space:]')" "3"
docker compose exec -T postgres psql -U postgres -d "$DP19_DB" -v ON_ERROR_STOP=1 -q -f - < db/dp19-seed-v3.sql >> "$EVIDENCE/dp19.log" 2>&1
assert_eq "V3 种子灌入退出码" "$?" "0"
DP19_COUNTS_SQL="select (select count(*) from review_run)||'/'||(select count(*) from outbox_command)||'/'||(select count(*) from publication_resource)||'/'||(select count(*) from webhook_inbox)||'/'||(select count(*) from review_finding)||'/'||(select count(*) from execution_event)||'/'||(select count(*) from run_step)||'/'||(select count(*) from work_item)||'/'||(select count(*) from step_attempt)"
C_BEFORE=$(m2_psql_db "$DP19_DB" "$DP19_COUNTS_SQL" | tr -d '[:space:]')
assert_eq "种子行数（run/outbox/resource/inbox/finding/event/step/work/attempt）" "$C_BEFORE" "1/2/2/1/1/1/1/1/1"
docker compose run --rm -T --no-deps -e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$DP19_DB" \
    migrate -connectRetries=30 migrate >> "$EVIDENCE/dp19.log" 2>&1
assert_eq "原地升级后 flyway 版本 == 迁移文件最大版本" \
    "$(m2_psql_db "$DP19_DB" "select max(version::int) from flyway_schema_history where success" | tr -d '[:space:]')" "$EXPECT_V"
C_AFTER=$(m2_psql_db "$DP19_DB" "$DP19_COUNTS_SQL" | tr -d '[:space:]')
assert_eq "升级数据零丢失（九表行数不变）" "$C_AFTER" "$C_BEFORE"
assert_eq "旧 Run 记录可读（trigger_key）" \
    "$(m2_psql_db "$DP19_DB" "select trigger_key from review_run where id='00000000-0000-4000-8000-0000000d1903'" | tr -d '[:space:]')" "dp19-seed"
assert_eq "旧 MISSING 资源行可读（V3 巡检列原样）" \
    "$(m2_psql_db "$DP19_DB" "select state||'/'||check_error_count from publication_resource where id='00000000-0000-4000-8000-0000000d1921'" | tr -d '[:space:]')" "MISSING/0"
assert_eq "V4 新列 replaces_resource_id 默认空、可读" \
    "$(m2_psql_db "$DP19_DB" "select coalesce(replaces_resource_id::text,'<null>') from publication_resource where id='00000000-0000-4000-8000-0000000d1920'" | tr -d '[:space:]')" "<null>"
assert_eq "V4 新表 step_checkpoint 已建（空表）" "$(m2_psql_db "$DP19_DB" "select count(*) from step_checkpoint" | tr -d '[:space:]')" "0"
assert_eq "V4 新表 repair_request 已建（空表）" "$(m2_psql_db "$DP19_DB" "select count(*) from repair_request" | tr -d '[:space:]')" "0"
assert_eq "升级后 publisher 对 outbox_command INSERT 仍无（权限不放宽）" \
    "$(m2_psql_db "$DP19_DB" "select has_table_privilege('publisher_app','outbox_command','INSERT')" | tr -d '[:space:]')" "f"
assert_eq "升级后 publisher 对 step_checkpoint SELECT 无" \
    "$(m2_psql_db "$DP19_DB" "select has_table_privilege('publisher_app','step_checkpoint','SELECT')" | tr -d '[:space:]')" "f"
assert_eq "升级后 publisher 对 repair_request 表级 INSERT 无（列级授权不提升表级 ACL，TB-11 裁定）" \
    "$(m2_psql_db "$DP19_DB" "select has_table_privilege('publisher_app','repair_request','INSERT')" | tr -d '[:space:]')" "f"
assert_eq "升级后 publisher 对 repair_request 列级 INSERT 有（state 列为代表列）" \
    "$(m2_psql_db "$DP19_DB" "select has_column_privilege('publisher_app','repair_request','state','INSERT')" | tr -d '[:space:]')" "t"
psql "drop database if exists $DP19_DB" > /dev/null 2>&1 \
    && echo "  复原：临时库 $DP19_DB 已删除" | tee -a "$SUMMARY"

# ---------------------------------------------------------------- DP-20
# V5 权限矩阵（model_call_ledger，§4.1；CT-30/31 的部署面复核）。
# flyway 版本门禁 = DP-01 的动态 EXPECT_V（迁移目录最大版本），V5 落地后自动跟随。
echo "== DP-20 V5 权限矩阵（model_call_ledger） ==" | tee -a "$SUMMARY"
assert_eq "control 对 model_call_ledger SELECT"  "$(tp control_app model_call_ledger SELECT)" "t"
assert_eq "control 对 model_call_ledger INSERT"  "$(tp control_app model_call_ledger INSERT)" "t"
assert_eq "control 对 model_call_ledger 表级 UPDATE 无" "$(tp control_app model_call_ledger UPDATE)" "f"
assert_eq "control 对 model_call_ledger DELETE 无" "$(tp control_app model_call_ledger DELETE)" "f"
for col in state outcome finished_at total_tokens cost_micros error_code; do
    assert_eq "control 列级 UPDATE $col（终态列放行）" "$(cp_ control_app model_call_ledger $col UPDATE)" "t"
done
for col in id invocation_id call_seq review_run_id run_step_id attempt_id route_id requested_model started_at; do
    assert_eq "control 列级 UPDATE $col 无（身份/谱系不可变）" "$(cp_ control_app model_call_ledger $col UPDATE)" "f"
done
for p in SELECT INSERT UPDATE DELETE; do
    assert_eq "publisher 对 model_call_ledger $p 零权限" "$(tp publisher_app model_call_ledger $p)" "f"
done
# PUBLIC 是伪角色，has_table_privilege 的 regrole 转换不认；改查授权目录本体（=0 行授权）
assert_eq "PUBLIC 对 model_call_ledger 零授权行" \
    "$(psql "select count(*) from information_schema.table_privileges where table_schema='public' and table_name='model_call_ledger' and grantee='PUBLIC'" | tr -d '[:space:]')" "0"

# ---------------------------------------------------------------- DP-21
# 启动自检矩阵（§4.9，负例）：一次性容器注入非法配置 → 拒启（exited 非零）+
# 日志点名旋钮、不回显密钥本体（EX-45）。负例在 bean 装配期失败，不触库写。
# 说明（诚实清单）：M3 校验失败发生在 Spring 上下文刷新期，日志为
# BeanCreationException 包装的配置异常信息，不经 StartupSelfCheckRunner 的
# "启动自检失败" 统一前缀——本门断言校验信息本体 + 非零退出码。
echo "== DP-21 启动自检矩阵（半配置/隐藏重试/数值/租约不等式，负例） ==" | tee -a "$SUMMARY"
dp_boot_refuse() { # <名称> <日志期望子串> [-e ...]...
    local name="$1" expect="$2"; shift 2
    local cname="dp21-$name"
    docker rm -f "$cname" >/dev/null 2>&1 || true
    docker compose run -d -T --no-deps --name "$cname" "$@" control-app >/dev/null 2>&1
    local deadline=$((SECONDS + 150)) st ec
    while [ $SECONDS -lt $deadline ]; do
        st=$(docker inspect "$cname" -f '{{.State.Status}}' 2>/dev/null)
        [ "$st" = "exited" ] && break
        sleep 3
    done
    docker logs "$cname" > "$EVIDENCE/$cname.log" 2>&1
    ec=$(docker inspect "$cname" -f '{{.State.ExitCode}}' 2>/dev/null)
    if [ "$st" = "exited" ] && [ "$ec" != "0" ]; then
        ok "$name 被拒启（exited/$ec）"
    else
        bad "$name 未拒启（State=$st ExitCode=$ec）"
    fi
    assert_contains "$name 日志点名校验规则" "$EVIDENCE/$cname.log" "$expect"
    docker rm -f "$cname" >/dev/null 2>&1 || true
}
dp_boot_refuse retry10  "spring.ai.retry.max-attempts 必须为 1" -e SPRING_AI_RETRY_MAXATTEMPTS=5
dp_boot_refuse keyblank "AGENT_MODEL_API_KEY 缺失或为占位符"     -e AGENT_MODEL_API_KEY=
dp_boot_refuse keyph    "AGENT_MODEL_API_KEY 缺失或为占位符"     -e AGENT_MODEL_API_KEY=placeholder-not-configured
dp_boot_refuse retryneg "maxCallRetries 不能为负"                -e APP_MODEL_MAXCALLRETRIES=-1
dp_boot_refuse circuit0 "failureThreshold 必须为正"              -e APP_MODEL_CIRCUIT_FAILURETHRESHOLD=0
dp_boot_refuse percall  "perCallTimeout 不得大于 gatewayTotalDeadline" -e APP_MODEL_PERCALLTIMEOUTMS=400000
dp_boot_refuse recovery "app.model.ledger.recovery-after-seconds 必须 >= 2 × per-call-timeout" \
    -e APP_MODEL_LEDGER_RECOVERYAFTERSECONDS=10
# lease 负例必须自带非法组合：stub 演练窗 .env 合法三元组 lease=60/deadline=30000
# 使单注 lease=60 反而合法（INC-61）；显式注入 deadline=55000 → 60 ≤ 55+10 必拒，
# 混合窗（deadline 默认 300000）同被覆写同拒——两种模式语义一致
# TB-28：compose 环境块硬编码 percall=120000（INC-61 透传默认），120000>55000 会先炸
# per-call 校验（拒启正确但点名不中）；补第三键 percall=20000（20000≤55000 合法），
# 校验链才能走到 lease 不等式
dp_boot_refuse lease    "app.worker.max-lease-seconds 必须大于 app.model.gateway.total-deadline-ms" \
    -e APP_WORKER_MAXLEASESECONDS=60 -e APP_MODEL_GATEWAY_TOTALDEADLINEMS=55000 \
    -e APP_MODEL_PERCALLTIMEOUTMS=20000

# ---------------------------------------------------------------- DP-22
# 账本冒烟（I29 两段记账）：stub 模型固定 usage（100/50/150）下 DP-05 的评审
# 应在 model_call_ledger 留下恰好一条 STARTED→SUCCEEDED；无悬挂 STARTED。
# 真实模型模式 token 数由百炼决定，本门记 [SKIP]（同 DP-17 惯例）。
echo "== DP-22 账本冒烟（两段记账恰好一条） ==" | tee -a "$SUMMARY"
if ! m2_stub_model_mode; then
    echo "  [SKIP] DP-22 需 stub 模型模式（固定 usage）；当前 OPENAI_COMPAT_BASE_URL=${OPENAI_COMPAT_BASE_URL:-未设}" | tee -a "$SUMMARY"
else
    L22_WHERE="from model_call_ledger ml join review_run rr on rr.id=ml.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR05_NUM"
    psql "select ml.state, ml.outcome, ml.prompt_tokens, ml.completion_tokens, ml.total_tokens, ml.usage_missing, ml.route_role, ml.requested_model $L22_WHERE order by ml.started_at" \
        | tee "$EVIDENCE/dp22-ledger.txt"
    assert_eq "DP-05 评审账本恰 1 行（无重复计费）" "$(psql "select count(*) $L22_WHERE" | tr -d '[:space:]')" "1"
    assert_eq "账本行 SUCCEEDED" "$(psql "select count(*) $L22_WHERE and ml.state='SUCCEEDED'" | tr -d '[:space:]')" "1"
    assert_eq "账本 outcome=OK" "$(psql "select count(*) $L22_WHERE and ml.outcome='OK'" | tr -d '[:space:]')" "1"
    assert_eq "stub 固定 usage 100/50/150 落账" \
        "$(psql "select count(*) $L22_WHERE and ml.prompt_tokens=100 and ml.completion_tokens=50 and ml.total_tokens=150" | tr -d '[:space:]')" "1"
    assert_eq "usage_missing=false" "$(psql "select count(*) $L22_WHERE and ml.usage_missing=false" | tr -d '[:space:]')" "1"
    assert_eq "route_role=PRIMARY（单路由无 fallback）" \
        "$(psql "select count(*) $L22_WHERE and ml.route_role='PRIMARY' and ml.fallback_from is null" | tr -d '[:space:]')" "1"
    # 悬挂 STARTED 兜底：超龄（>10min，recovery-after 默认 240s + 扫描周期 60s 两倍余量）
    # 的 STARTED 行应已被 ModelCallLedgerRecovery 标 UNKNOWN（DP-17 SIGKILL 残骸即案例）
    assert_eq "无悬挂 STARTED（超龄全部已收敛 UNKNOWN）" \
        "$(psql "select count(*) from model_call_ledger where state='STARTED' and started_at < now() - interval '10 minutes'" | tr -d '[:space:]')" "0"
fi

# ---------------------------------------------------------------- DP-23
# V5 全约束种子（§4.1 全 CHECK/唯一/FK 的部署面复验；CT-35~39 对应）。
# 手法：超库身份（约束级，不含权限面——权限面在 DP-20/24）；合法行以 route_id='dp23'
# 标记，结尾定点删除，零残留。
echo "== DP-23 V5 全约束种子（非法逐条拒绝） ==" | tee -a "$SUMMARY"
# 父行选择必须沿 FK 链取（reconciler 会持续合成无 step 的新 Run，"最新 Run"不可靠）
RUN23=$(psql "select r.id from review_run r join run_step s on s.review_run_id=r.id join step_attempt a on a.step_id=s.id order by r.created_at desc limit 1" | tr -d '[:space:]')
STEP23=$(psql "select id from run_step where review_run_id='$RUN23' limit 1" | tr -d '[:space:]')
ATT23=$(psql "select id from step_attempt where step_id='$STEP23' limit 1" | tr -d '[:space:]')
if [ -z "$RUN23" ] || [ -z "$STEP23" ] || [ -z "$ATT23" ]; then
    bad "DP-23 缺 FK 父行（run/step/attempt 需由 DP-05 等前置门产生；请全脚本顺序执行）"
else
    DP23_COLS="id,invocation_id,call_seq,review_run_id,run_step_id,attempt_id,lease_epoch,route_id,route_role,endpoint_scope,quota_scope,requested_model,state,outcome,http_status,prompt_tokens,completion_tokens,total_tokens,started_at,finished_at"
    DP23_BASE="'11111111-1111-4111-8111-111111111101',1,'$RUN23','$STEP23','$ATT23',1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),now()"
    dp23_reject() { # <描述> <values 覆写后的完整 SQL>
        if psql "$1" >/dev/null 2>"$EVIDENCE/dp23-last-err.txt"; then
            bad "$2：未被数据库拒绝"
        else
            ok "$2（已拒绝：$(head -c 80 "$EVIDENCE/dp23-last-err.txt" | tr '\n' ' ')…）"
        fi
    }
    # 基线：合法行可插入
    if psql "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),$DP23_BASE)" >/dev/null 2>&1; then
        ok "合法种子行插入成功（终态 SUCCEEDED 形态自洽）"
    else
        bad "合法种子行插入失败（合法形态被误伤，后续拒绝断言不可信）"
    fi
    assert_eq "合法行已落（route_id=dp23 恰 1）" \
        "$(psql "select count(*) from model_call_ledger where route_id='dp23'" | tr -d '[:space:]')" "1"
    dp23_reject "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),$DP23_BASE)" \
        "UNIQUE(invocation_id,call_seq) 重复拒绝"
    dp23_reject "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111102',2,'$RUN23','$STEP23','$ATT23',1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','DP23BOGUS',null,null,null,0,0,0,now(),null)" \
        "state CHECK 拒绝非法状态"
    dp23_reject "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111103',2,'$RUN23','$STEP23','$ATT23',1,'dp23','FALLBACK','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),now())" \
        "lineage CHECK：FALLBACK 缺 fallback_from 拒绝"
    dp23_reject "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111104',2,'$RUN23','$STEP23','$ATT23',1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),null)" \
        "状态机 CHECK：SUCCEEDED 缺 finished_at 拒绝"
    dp23_reject "insert into model_call_ledger($DP23_COLS,usage_missing) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111105',2,'$RUN23','$STEP23','$ATT23',1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),now(),true)" \
        "usage_missing=true 但计数非零拒绝"
    dp23_reject "insert into model_call_ledger($DP23_COLS,cost_micros) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111106',2,'$RUN23','$STEP23','$ATT23',1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),now(),5)" \
        "cost_micros 非空但价格快照缺失拒绝"
    dp23_reject "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111107',0,'$RUN23','$STEP23','$ATT23',1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),now())" \
        "call_seq=0 拒绝（CHECK call_seq>=1）"
    dp23_reject "insert into model_call_ledger($DP23_COLS) values (gen_random_uuid(),'11111111-1111-4111-8111-111111111108',2,'$RUN23','$STEP23',gen_random_uuid(),1,'dp23','PRIMARY','dp23-ep','dp23-q','dp23-model','SUCCEEDED','OK',200,1,1,2,now(),now())" \
        "FK：不存在 attempt_id 拒绝"
    psql "delete from model_call_ledger where route_id='dp23'" > /dev/null
    assert_eq "复原：dp23 种子零残留" \
        "$(psql "select count(*) from model_call_ledger where route_id='dp23'" | tr -d '[:space:]')" "0"
fi

# ---------------------------------------------------------------- DP-24
# 完整权限矩阵行为面复核（DP-20 位面之外的实库尝试；事务内验证后 rollback，零残留）。
echo "== DP-24 V5 权限行为面（SET ROLE 实库尝试） ==" | tee -a "$SUMMARY"
dp24_try() { # <描述> <role> <SQL> <期望：reject|ok>
    local desc="$1" role="$2" sql="$3" expect="$4" out
    out=$(docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tA 2>&1 <<SQL
BEGIN; SET LOCAL ROLE $role; $sql; ROLLBACK;
SQL
)
    if [ "$expect" = reject ]; then
        echo "$out" | grep -qiE "permission denied|42501" && ok "$desc（已拒）" || bad "$desc：未被拒绝（$out）"
    else
        echo "$out" | grep -qiE "permission denied|42501|ERROR" && bad "$desc：被误拒（$out）" || ok "$desc（放行）"
    fi
}
dp24_try "publisher SELECT model_call_ledger"  publisher_app "select count(*) from model_call_ledger" reject
dp24_try "publisher INSERT model_call_ledger"  publisher_app "insert into model_call_ledger(id,invocation_id,call_seq,review_run_id,run_step_id,attempt_id,lease_epoch,route_id,route_role,endpoint_scope,quota_scope,requested_model,state) values (gen_random_uuid(),gen_random_uuid(),1,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),1,'dp24','PRIMARY','e','q','m','STARTED')" reject
dp24_try "publisher UPDATE model_call_ledger"  publisher_app "update model_call_ledger set state=state where false" reject
dp24_try "control 列级越权 UPDATE requested_model（身份列）" control_app "update model_call_ledger set requested_model='x' where false" reject
dp24_try "control 列级 UPDATE state（终态列，WHERE false 零行）" control_app "update model_call_ledger set state=state where false" ok

# ---------------------------------------------------------------- DP-25
# 主备配置矩阵（裁定 C-2，§4.9 半配置规则）：正例指向独立临时库（防第二 control
# worker 在主库抢活）；负例同 DP-21 拒启判据。
echo "== DP-25 主备配置矩阵 ==" | tee -a "$SUMMARY"
DP25_DB="dp25_boot"
psql "drop database if exists $DP25_DB" > /dev/null 2>&1
psql "create database $DP25_DB" > "$EVIDENCE/dp25.log" 2>&1
docker compose run --rm -T --no-deps -e "FLYWAY_URL=jdbc:postgresql://postgres:5432/$DP25_DB" \
    migrate -connectRetries=30 migrate >> "$EVIDENCE/dp25.log" 2>&1
assert_eq "临时库 $DP25_DB 迁移退出码" "$?" "0"
dp_boot_ok() { # <名称> [-e ...]...（POSTGRES_DB 固定指临时库）
    local name="$1"; shift
    local cname="dp25-$name" deadline ok_=0 st
    docker rm -f "$cname" >/dev/null 2>&1 || true
    docker compose run -d -T --no-deps --name "$cname" -e POSTGRES_DB="$DP25_DB" "$@" control-app >/dev/null 2>&1
    deadline=$((SECONDS + 180))
    while [ $SECONDS -lt $deadline ]; do
        if docker logs "$cname" 2>&1 | grep -q "启动自检通过"; then ok_=1; break; fi
        st=$(docker inspect "$cname" -f '{{.State.Status}}' 2>/dev/null)
        [ "$st" = "exited" ] && break
        sleep 3
    done
    docker logs "$cname" > "$EVIDENCE/$cname.log" 2>&1
    [ "$ok_" = 1 ] && ok "$name 启动自检通过" || bad "$name 未通过启动自检（详见 $EVIDENCE/$cname.log）"
    docker rm -f "$cname" >/dev/null 2>&1 || true
}
dp_boot_ok inherit-only -e AGENT_MODEL_FALLBACK=qwen-max
dp_boot_ok independent  -e AGENT_MODEL_FALLBACK=qwen-max \
    -e OPENAI_COMPAT_BASE_URL_FALLBACK=http://github-stub:8080 -e AGENT_MODEL_API_KEY_FALLBACK=dp25-other-key-1
dp_boot_refuse dup-route-id "主备 route_id 相同" \
    -e AGENT_MODEL_FALLBACK=qwen-max -e APP_MODEL_ROUTE_PRIMARY_ID=dup -e APP_MODEL_ROUTE_FALLBACK_ID=dup
# 五元组全同（fallback 模型=主模型 + key 显式=主 key + 端点继承）→ 拒绝；且日志不回显 key 本体
docker rm -f dp21-fivetuple >/dev/null 2>&1 || true
docker compose run -d -T --no-deps --name dp21-fivetuple \
    -e AGENT_MODEL_FALLBACK="$AGENT_MODEL" -e AGENT_MODEL_API_KEY_FALLBACK="$AGENT_MODEL_API_KEY" \
    control-app >/dev/null 2>&1
FT_DEADLINE=$((SECONDS + 150))
while [ $SECONDS -lt $FT_DEADLINE ]; do
    [ "$(docker inspect dp21-fivetuple -f '{{.State.Status}}' 2>/dev/null)" = "exited" ] && break
    sleep 3
done
docker logs dp21-fivetuple > "$EVIDENCE/dp21-fivetuple.log" 2>&1
FT_EC=$(docker inspect dp21-fivetuple -f '{{.State.ExitCode}}' 2>/dev/null)
[ "$FT_EC" != "0" ] && ok "主备五元组全同被拒启（ExitCode=$FT_EC）" || bad "主备五元组全同未拒启"
assert_contains "日志点名五元组规则" "$EVIDENCE/dp21-fivetuple.log" "主备五元组完全相同"
if grep -qF "$AGENT_MODEL_API_KEY" "$EVIDENCE/dp21-fivetuple.log"; then
    bad "密钥值泄漏进日志（EX-45 违例）"
else
    ok "密钥值未进日志（EX-45 不回显）"
fi
docker rm -f dp21-fivetuple >/dev/null 2>&1 || true
psql "drop database if exists $DP25_DB" > /dev/null 2>&1 \
    && echo "  复原：临时库 $DP25_DB 已删除" | tee -a "$SUMMARY"

# ---------------------------------------------------------------- DP-26
# 密钥挂载：key 只从环境变量注入；运行日志零回显；容器无 key 文件挂载。
echo "== DP-26 密钥挂载与零回显 ==" | tee -a "$SUMMARY"
CID26=$(docker compose ps -q control-app)
docker inspect "$CID26" -f '{{json .Config.Env}}' > "$EVIDENCE/dp26-control-env.json"
grep -q "AGENT_MODEL_API_KEY" "$EVIDENCE/dp26-control-env.json" \
    && ok "control 经环境变量注入 AGENT_MODEL_API_KEY" || bad "control 缺 AGENT_MODEL_API_KEY 环境注入"
docker inspect "$CID26" -f '{{json .Mounts}}' | grep -qiE "api.?key|model.?key" \
    && bad "control 出现模型密钥文件挂载" || ok "control 无模型密钥文件挂载（仅 env 通道）"
docker compose logs --no-color control-app > "$EVIDENCE/dp26-control.log" 2>&1
if grep -qF "$AGENT_MODEL_API_KEY" "$EVIDENCE/dp26-control.log"; then
    bad "运行日志含密钥值（EX-45 违例）"
else
    ok "运行日志零密钥值"
fi
grep -q 'AGENT_MODEL_API_KEY: ${AGENT_MODEL_API_KEY' docker-compose.yml \
    && ok "compose 中密钥为变量引用（非字面量）" || bad "compose 中密钥疑似硬编码"

# ---------------------------------------------------------------- DP-27
# Gateway 冒烟（I30）：stub 固定 usage 的成功调用 → 账本、checkpoint、发布三面一致。
# 真实模型模式记 [SKIP]（同 DP-17/22 惯例）。
echo "== DP-27 Gateway 冒烟（账本↔checkpoint↔发布一致） ==" | tee -a "$SUMMARY"
if ! m2_stub_model_mode; then
    echo "  [SKIP] DP-27 需 stub 模型模式；当前 OPENAI_COMPAT_BASE_URL=${OPENAI_COMPAT_BASE_URL:-未设}" | tee -a "$SUMMARY"
else
    EXPECT_IDENTITY="openai-compatible/${AGENT_MODEL:-qwen-plus}/configured"
    psql "select sc.model_identity, ml.run_step_id = sc.step_id as same_step from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id left join model_call_ledger ml on ml.run_step_id = rs.id where s.pr_number=$PR05_NUM" \
        | tee "$EVIDENCE/dp27-cross.txt"
    assert_eq "checkpoint model_identity=实际路由身份（I30）" \
        "$(psql "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR05_NUM and sc.model_identity='$EXPECT_IDENTITY'" | tr -d '[:space:]')" "1"
    assert_eq "账本行与 checkpoint 同属一个 step" \
        "$(psql "select count(*) from model_call_ledger ml join step_checkpoint sc on sc.step_id=ml.run_step_id join run_step rs on rs.id=ml.run_step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.pr_number=$PR05_NUM" | tr -d '[:space:]')" "1"
    assert_eq "发布面已 CONFIRMED（引用 DP-05 结果复核）" "$CONFIRMED" "2"
fi

# ---------------------------------------------------------------- DP-28
# 熔断/时限旋钮复原（G2 硬门 H 系配套）：模式感知断言——
#   混合（默认）模式：.env 零声明，compose 渲染值/运行容器 env = 代码默认
#     （lease 600 / deadline 300000 / percall 120000）；
#   演练窗口（.env 声明 APP_MODEL_GATEWAY_TOTALDEADLINEMS）：合法三元组
#     lease=60/deadline=30000/percall=20000 必须精确成对（F-22 不等式链，
#     见 compose 注释/INC-61），其余取值 = 残留或错配。
# 从未接线的旋钮（CIRCUIT/LEDGER/MAXCALL）三面恒零出现。
echo "== DP-28 熔断/时限旋钮（零演练残留/演练窗成对） ==" | tee -a "$SUMMARY"
docker compose config > "$EVIDENCE/dp28-compose-config.yml" 2>/dev/null
docker inspect "$(docker compose ps -q control-app)" -f '{{json .Config.Env}}' > "$EVIDENCE/dp28-control-env.json"
dp28_cfg() { grep -oE "$1: \"?[0-9]+\"?" "$EVIDENCE/dp28-compose-config.yml" | head -1 | grep -oE '[0-9]+'; }
dp28_env() { grep -oE "$1=[0-9]+" "$EVIDENCE/dp28-control-env.json" | head -1 | cut -d= -f2; }
assert_eq "compose 无未接线旋钮（CIRCUIT/LEDGER/MAXCALL）" \
    "$(grep -cE 'APP_MODEL_(CIRCUIT|LEDGER|MAXCALL)' "$EVIDENCE/dp28-compose-config.yml")" "0"
assert_eq "运行容器无未接线旋钮（CIRCUIT/LEDGER/MAXCALL）" \
    "$(grep -coE 'APP_MODEL_(CIRCUIT|LEDGER|MAXCALL)' "$EVIDENCE/dp28-control-env.json")" "0"
if grep -qE '^APP_MODEL_GATEWAY_TOTALDEADLINEMS=' .env; then
    # 演练窗口：三元组成对 + 未接线旋钮 .env 零声明
    assert_eq ".env 演练窗三元组齐备（3 行声明）" \
        "$(grep -cE '^(APP_WORKER_MAXLEASESECONDS|APP_MODEL_GATEWAY_TOTALDEADLINEMS|APP_MODEL_PERCALLTIMEOUTMS)=' .env)" "3"
    assert_eq ".env 无未接线旋钮声明" \
        "$(grep -cE '^APP_MODEL_(CIRCUIT|LEDGER|MAXCALL)' .env)" "0"
    assert_eq "演练窗 compose lease=60" "$(dp28_cfg APP_WORKER_MAXLEASESECONDS)" "60"
    assert_eq "演练窗 compose deadline=30000" "$(dp28_cfg APP_MODEL_GATEWAY_TOTALDEADLINEMS)" "30000"
    assert_eq "演练窗 compose percall=20000" "$(dp28_cfg APP_MODEL_PERCALLTIMEOUTMS)" "20000"
    assert_eq "演练窗容器 lease=60" "$(dp28_env APP_WORKER_MAXLEASESECONDS)" "60"
    assert_eq "演练窗容器 deadline=30000" "$(dp28_env APP_MODEL_GATEWAY_TOTALDEADLINEMS)" "30000"
    assert_eq "演练窗容器 percall=20000" "$(dp28_env APP_MODEL_PERCALLTIMEOUTMS)" "20000"
else
    # 混合（默认）模式：零声明 + 渲染值=代码默认（有声明无残留）
    assert_eq ".env 无熔断/时限旋钮声明" \
        "$(grep -cE '^APP_MODEL_(CIRCUIT|GATEWAY|LEDGER|PERCALL|MAXCALL)' .env)" "0"
    assert_eq "compose lease 渲染=默认 600" "$(dp28_cfg APP_WORKER_MAXLEASESECONDS)" "600"
    assert_eq "compose deadline 渲染=默认 300000" "$(dp28_cfg APP_MODEL_GATEWAY_TOTALDEADLINEMS)" "300000"
    assert_eq "compose percall 渲染=默认 120000" "$(dp28_cfg APP_MODEL_PERCALLTIMEOUTMS)" "120000"
    assert_eq "容器 lease=默认 600" "$(dp28_env APP_WORKER_MAXLEASESECONDS)" "600"
    assert_eq "容器 deadline=默认 300000" "$(dp28_env APP_MODEL_GATEWAY_TOTALDEADLINEMS)" "300000"
    assert_eq "容器 percall=默认 120000" "$(dp28_env APP_MODEL_PERCALLTIMEOUTMS)" "120000"
fi

# ---------------------------------------------------------------- 汇总
echo "==================================================" | tee -a "$SUMMARY"
echo "结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE" | tee -a "$SUMMARY"
[ "$FAIL" -eq 0 ]
