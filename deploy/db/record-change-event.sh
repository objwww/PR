#!/bin/sh
# ============================================================================
# EX-B1 部署事实落档助手（change_event，source='deployment'）
# 用法: record-change-event.sh <deploy_id> <SUCCEEDED|FAILED> <config_digest|''> \
#           <actor> [service] [image_digest|''] [commit_sha|''] [rollback_of|'']
# 写路径 = deploy_app 角色（SET ROLE，与 control_app 读写面分离，V40）；
# 幂等锚 (source, deploy_id) —— 脚本重试 ON CONFLICT DO NOTHING，零重复生效事件。
# 空串参数经 NULLIF 落 NULL。在 195 宿主执行（deploy-postgres-1 容器名惯例）。
# B-28（195 drill1 实证）：docker exec 无 -i 不转发 stdin——heredoc 静默零执行、
#   psql 空入退出 0 谎报成功；nohup/断连下 -i 亦 EOF 不可靠 → SQL 走 -c argv 面。
# B-29（195 探针实证）：该 psql 的 -c 不做 :'var' 插值（select :'x' → 42601；
#   stdin 同参插值正常但即 B-28 死面）→ 值在 shell 侧内联，单引号 '' 翻倍转义。
# B-30（195 drill3 实证）：INSERT..RETURNING 需要 RETURNING 列的 SELECT 特权——
#   deploy_app insert-only 被拒（B-25 同律第二面：目标列 SELECT → RETURNING 列
#   SELECT；IT 种子无 RETURNING 故 IT 面不可见）→ 改 reset role 后以超级用户
#   count 回读验证（角色授权面零松动，防谎报能力不丢）。
# B-31（195 演练证据 [3] 实证）：单次事后 count 无法区分"新插入"与"本已存在"——
#   重放也印 RECORDED（假阳性；锚本身零新增由 count 不变实证）→ 改 前/后双 count
#   对照：0→1 = RECORDED；1→1 = SKIPPED_IDEMPOTENT。
# 输出互斥两态：CHANGE_EVENT_RECORDED（确有一行落库）/ CHANGE_EVENT_SKIPPED_IDEMPOTENT
#   （锚命中零新增）；psql 失败经 set -eu 传退出码。
# ============================================================================
set -eu

DEPLOY_ID="$1"; STATUS="$2"; CONFIG_DIGEST="${3:-}"; ACTOR="$4"
SERVICE="${5:-control-app}"; IMAGE_DIGEST="${6:-}"; COMMIT_SHA="${7:-}"; ROLLBACK_OF="${8:-}"

esc() { printf "%s" "$1" | sed "s/'/''/g"; }

SQL="select count(*) from change_event
 where source = 'deployment' and deploy_id = '$(esc "$DEPLOY_ID")';
set role deploy_app;
insert into change_event (id, deploy_id, source, action, service, environment,
    image_digest, config_digest, commit_sha, actor, started_at, effective_at,
    rollback_of, status)
values (gen_random_uuid(), '$(esc "$DEPLOY_ID")', 'deployment', 'DEPLOY', '$(esc "$SERVICE")',
    'production', NULLIF('$(esc "$IMAGE_DIGEST")', ''), NULLIF('$(esc "$CONFIG_DIGEST")', ''),
    NULLIF('$(esc "$COMMIT_SHA")', ''), '$(esc "$ACTOR")', now(), now(),
    NULLIF('$(esc "$ROLLBACK_OF")', ''), '$(esc "$STATUS")')
on conflict do nothing;
reset role;
select count(*) from change_event
 where source = 'deployment' and deploy_id = '$(esc "$DEPLOY_ID")';"

COUNTS=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent \
    -v ON_ERROR_STOP=1 -q -t -A -c "$SQL")

PRE=$(printf "%s" "$COUNTS" | sed -n 1p)
POST=$(printf "%s" "$COUNTS" | sed -n '$p')

if [ "$PRE" = "0" ] && [ "$POST" = "1" ]; then
    echo "CHANGE_EVENT_RECORDED: deploy_id=$DEPLOY_ID status=$STATUS"
elif [ "$PRE" = "1" ] && [ "$POST" = "1" ]; then
    echo "CHANGE_EVENT_SKIPPED_IDEMPOTENT: deploy_id=$DEPLOY_ID (anchor hit)"
else
    echo "CHANGE_EVENT_VERIFY_FAILED: pre=$PRE post=$POST（锚面异常，须人工核查）" >&2
    exit 1
fi
