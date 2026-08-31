-- ============================================================================
-- V3 —— M1 入口可信化（docs/M1-技术方案.md v1.2 §4.1，逐条可回指）
--   新表 webhook_inbox（六态 + 租约 + raw/jsonb 双列，修正 #2/#3/#4）
--   pr_subject + last_event_updated_at / next_pr_reconcile_at / pr_reconcile_error_count
--   publication_resource 观测态迁移（ACTIVE→PRESENT、DRIFTED→MISSING）+ 巡检三列（措辞修正 #2）
--   review_run 活跃世代部分唯一索引（E2E-20 DB 级兜底）
--   授权：inbox 归 control；publication_resource 观测列级授权（CT-11/19/20）
-- ============================================================================

-- ---------- webhook_inbox（冻结 §5.7 基线 + 修正 #2 租约 / #3 六态 / #4 raw+jsonb） ----------

create table webhook_inbox (
    delivery_id              text primary key,
    github_event             text not null,
    github_action            text,
    github_installation_id   bigint,
    github_repository_id     bigint,

    payload_raw              bytea not null,    -- HMAC 复核与审计的唯一权威（CT-18）
    payload_json             jsonb,             -- 可空（E2E-22：合法签名+畸形 JSON 也要能落库审计）
    payload_digest           char(64) not null, -- sha256(payload_raw)，重投比对用

    state                    varchar(24) not null,
    lease_owner              text,
    lease_until              timestamptz,
    lease_epoch              bigint not null default 0,

    attempt_count            integer not null default 0,
    max_attempts             integer not null default 5,
    next_retry_at            timestamptz,
    last_error               jsonb,

    received_at              timestamptz not null,
    updated_at               timestamptz not null,
    processed_at             timestamptz,

    constraint ck_inbox_state
        check (state in ('RECEIVED','PROCESSING','RETRY_WAIT',
                         'PROCESSED','IGNORED','DEAD_LETTER')),

    constraint ck_inbox_attempts
        check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts)
);

-- 领取：公平排序（修正 #7 同原则：防 LIMIT 饿死尾部）
create index ix_inbox_claim on webhook_inbox(next_retry_at, received_at)
    where state in ('RECEIVED','RETRY_WAIT');

-- 崩溃回收：租约过期的 PROCESSING 可被重领
create index ix_inbox_lease on webhook_inbox(lease_until)
    where state = 'PROCESSING';

-- ---------- pr_subject：LWW 水印 + PR State Reconciler 公平扫描（修正 #6/#7） ----------

alter table pr_subject add column last_event_updated_at timestamptz;
alter table pr_subject add column next_pr_reconcile_at timestamptz not null default now();
alter table pr_subject add column pr_reconcile_error_count integer not null default 0;

create index ix_pr_subject_reconcile on pr_subject(next_pr_reconcile_at)
    where state = 'OPEN';

-- ---------- publication_resource：观测态迁移 + Drift Reconciler 巡检列 ----------

alter table publication_resource add column next_check_at timestamptz not null default now();
alter table publication_resource add column last_checked_at timestamptz;
alter table publication_resource add column check_error_count integer not null default 0;

-- 顺序敏感（INC-20）：先放旧约束 → 再迁数据 → 最后加新约束。
-- 反序会在有数据的库上立即违反旧 CHECK（空库沙箱验不出来）。
alter table publication_resource drop constraint ck_pub_resource_state;
update publication_resource set state = 'PRESENT' where state = 'ACTIVE';
update publication_resource set state = 'MISSING' where state = 'DRIFTED';
alter table publication_resource add constraint ck_pub_resource_state
    check (state in ('PRESENT','MISSING','UNKNOWN','RETIRED','REPAIRED'));

create index ix_pub_resource_check on publication_resource(next_check_at)
    where state = 'PRESENT';

-- ---------- review_run：同 revision 同策略代活跃 Run 唯一（E2E-20 兜底） ----------
-- 真正防线是 T1 在 pr_subject 行锁下 check-then-insert（ST-21 收敛点）；
-- 本索引是并发洞穿的最后防线。run_key 含 trigger_key，拦不住双源并发。

-- INC-21 数据规整（有库先收编）：M0 时期无此约束，测试残留同世代多活跃 Run。
-- 每代只留最新一行活跃，其余置 SUPERSEDED（投影可变、账本不动；空库/全新部署为 no-op）。
update review_run r set state = 'SUPERSEDED', updated_at = now()
 where state not in ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
   and exists (
       select 1 from review_run newer
        where newer.pr_revision_id  = r.pr_revision_id
          and newer.policy_version  = r.policy_version
          and newer.prompt_version  = r.prompt_version
          and newer.toolset_version = r.toolset_version
          and newer.state not in ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED')
          and (newer.created_at, newer.id) > (r.created_at, r.id)
   );

create unique index uq_review_run_active_gen
    on review_run(pr_revision_id, policy_version, prompt_version, toolset_version)
    where state not in ('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED','SUPERSEDED');

-- ---------- 授权 ----------

grant select, insert, update on webhook_inbox to control_app;
-- publisher_app 对 webhook_inbox 零权限（默认无 grant；CT-19 显式断言，含 SELECT 也拒）

-- publication_resource：publisher 从"整行 update"收窄为"只能更新观测列"（措辞修正 #2 / CT-20）
revoke update on publication_resource from publisher_app;
grant update (state, drift_detected_at, last_checked_at, next_check_at,
              check_error_count, updated_at) on publication_resource to publisher_app;
-- control 对 publication_resource 保持只读（V2 已 grant select；CT-20 断言无 update）
