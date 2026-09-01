-- ============================================================================
-- DP-19 种子数据：V3 形态历史数据（docs/M2-技术方案.md §11 部署门 DP-19，
-- 回指 CT-22 / 评审 #17：带历史数据原地升 V4 —— 数据零丢失 + 旧记录可读 +
-- 权限不放宽）。
-- 只插 V1/V3 已有列（step_checkpoint/repair_request/replaces_resource_id 等
-- V4 对象此刻尚不存在——这正是"升级前"的语义）；全部固定 UUID 便于断言。
-- 目标库：DP-19 临时库 dp19_upgrade（flyway -target=3 建到 V3 后灌入）。
-- ============================================================================
begin;

-- ---------- PR 主体 / Revision（V1 形态） ----------
insert into pr_subject (
    id, github_installation_id, github_repository_id, repository_full_name, pr_number,
    state, draft, merged, current_revision_id, current_policy_version,
    publication_epoch, next_outbox_sequence, last_resolved_sequence, version,
    created_at, updated_at
) values (
    '00000000-0000-4000-8000-0000000d1901', 555000, 9001, 'stuborg/stubrepo', 999901,
    'OPEN', false, false, null, 'policy-v1',
    3, 3, 2, 0,
    '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z'
);

insert into pr_revision (
    id, pr_subject_id, head_sha, base_ref, base_sha, merge_base_sha,
    diff_digest, source_snapshot_digest, revision_fingerprint, observed_at, created_at
) values (
    '00000000-0000-4000-8000-0000000d1902', '00000000-0000-4000-8000-0000000d1901',
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'main',
    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', null,
    repeat('a', 64), repeat('b', 64), repeat('c', 64),
    '2026-08-01T00:01:00Z', '2026-08-01T00:01:00Z'
);

update pr_subject set current_revision_id = '00000000-0000-4000-8000-0000000d1902'
 where id = '00000000-0000-4000-8000-0000000d1901';

-- ---------- V3 形态 Run（NORMAL，COMPLETED 终态） ----------
insert into review_run (
    id, pr_revision_id, parent_run_id, root_run_id, run_key, trigger_key, run_mode,
    policy_version, prompt_version, toolset_version, initial_model_route,
    state, publisher_disabled, version, created_at, updated_at, completed_at
) values (
    '00000000-0000-4000-8000-0000000d1903', '00000000-0000-4000-8000-0000000d1902',
    null, null, repeat('d', 64), 'dp19-seed', 'NORMAL',
    'policy-v1', 'prompt-v1', 'toolset-v1', null,
    'COMPLETED', false, 0,
    '2026-08-01T00:02:00Z', '2026-08-01T00:10:00Z', '2026-08-01T00:10:00Z'
);

insert into run_step (
    id, review_run_id, parent_step_id, step_key, operation_id, execution_scope,
    step_type, state, ordinal, max_attempts, timeout_seconds, version,
    created_at, updated_at, completed_at
) values (
    '00000000-0000-4000-8000-0000000d1904', '00000000-0000-4000-8000-0000000d1903',
    null, 'REVIEW', '00000000-0000-4000-8000-0000000d1912', 'root',
    'REVIEW', 'SUCCEEDED', 0, 3, 600, 0,
    '2026-08-01T00:02:00Z', '2026-08-01T00:09:00Z', '2026-08-01T00:09:00Z'
);

insert into work_item (
    id, review_run_id, step_id, work_type, state, priority, available_at,
    lease_owner, lease_until, lease_epoch, attempt_count, max_attempts,
    created_at, updated_at
) values (
    '00000000-0000-4000-8000-0000000d1905', '00000000-0000-4000-8000-0000000d1903',
    '00000000-0000-4000-8000-0000000d1904', 'STEP', 'DONE', 0, '2026-08-01T00:02:00Z',
    null, null, 1, 1, 3,
    '2026-08-01T00:02:00Z', '2026-08-01T00:09:00Z'
);

insert into step_attempt (
    id, step_id, work_item_id, attempt_no, lease_epoch, worker_id, status,
    started_at, finished_at
) values (
    '00000000-0000-4000-8000-0000000d1906', '00000000-0000-4000-8000-0000000d1904',
    '00000000-0000-4000-8000-0000000d1905', 1, 1, 'dp19-seed-worker', 'SUCCEEDED',
    '2026-08-01T00:02:10Z', '2026-08-01T00:09:00Z'
);

-- ---------- Outbox（V3 形态：两条 CONFIRMED） ----------
insert into outbox_command (
    operation_id, pr_subject_id, review_run_id, pr_revision_id,
    aggregate_key, aggregate_sequence, publication_epoch, fence_mode,
    command_type, state, policy_version, payload_hash, remote_identity_type,
    lease_epoch, attempt_count, max_attempts,
    remote_id, remote_url, reconcile_not_found_count,
    created_at, updated_at, confirmed_at
) values
( '00000000-0000-4000-8000-0000000d1910', '00000000-0000-4000-8000-0000000d1901',
  '00000000-0000-4000-8000-0000000d1903', '00000000-0000-4000-8000-0000000d1902',
  'stuborg/stubrepo#999901', 1, 3, 'CURRENT_EPOCH',
  'CREATE_CHECK', 'CONFIRMED', 'policy-v1', repeat('e', 64), 'CHECK_RUN',
  1, 1, 3,
  '7700001', 'http://stub.local/check-runs/7700001', 0,
  '2026-08-01T00:09:10Z', '2026-08-01T00:09:40Z', '2026-08-01T00:09:40Z' ),
( '00000000-0000-4000-8000-0000000d1911', '00000000-0000-4000-8000-0000000d1901',
  '00000000-0000-4000-8000-0000000d1903', '00000000-0000-4000-8000-0000000d1902',
  'stuborg/stubrepo#999901', 2, 3, 'CURRENT_EPOCH',
  'PUBLISH_REVIEW', 'CONFIRMED', 'policy-v1', repeat('f', 64), 'REVIEW',
  1, 1, 3,
  '8800001', 'http://stub.local/reviews/8800001', 0,
  '2026-08-01T00:09:20Z', '2026-08-01T00:09:50Z', '2026-08-01T00:09:50Z' );

-- ---------- 资源行（V3 形态：PRESENT 一行 + MISSING 一行，含巡检三列） ----------
insert into publication_resource (
    id, resource_type, created_by_operation_id, pr_subject_id,
    remote_id, remote_url, marker, state,
    next_check_at, last_checked_at, check_error_count,
    created_at, updated_at
) values
( '00000000-0000-4000-8000-0000000d1920', 'CHECK_RUN', '00000000-0000-4000-8000-0000000d1910',
  '00000000-0000-4000-8000-0000000d1901',
  '7700001', 'http://stub.local/check-runs/7700001', '00000000-0000-4000-8000-0000000d1910',
  'PRESENT', '2026-08-01T01:00:00Z', '2026-08-01T00:30:00Z', 0,
  '2026-08-01T00:09:40Z', '2026-08-01T00:30:00Z' ),
( '00000000-0000-4000-8000-0000000d1921', 'REVIEW', '00000000-0000-4000-8000-0000000d1911',
  '00000000-0000-4000-8000-0000000d1901',
  '8800001', 'http://stub.local/reviews/8800001',
  '<!-- ai-review:00000000-0000-4000-8000-0000000d1911 -->', 'MISSING',
  '2026-08-01T08:00:00Z', '2026-08-01T01:00:00Z', 0,
  '2026-08-01T00:09:50Z', '2026-08-01T01:00:00Z' );

update publication_resource set drift_detected_at = '2026-08-01T01:00:00Z'
 where id = '00000000-0000-4000-8000-0000000d1921';

-- ---------- webhook_inbox（V3 新表，PROCESSED 历史行） ----------
insert into webhook_inbox (
    delivery_id, github_event, github_action, github_installation_id, github_repository_id,
    payload_raw, payload_json, payload_digest, state,
    lease_epoch, attempt_count, max_attempts,
    received_at, updated_at, processed_at
) values (
    'dp19-seed-delivery', 'pull_request', 'opened', 555000, 9001,
    '\x7b7d'::bytea, '{}'::jsonb, repeat('1', 64), 'PROCESSED',
    1, 1, 5,
    '2026-08-01T00:00:30Z', '2026-08-01T00:01:00Z', '2026-08-01T00:01:00Z'
);

-- ---------- finding + 账本（V1 形态） ----------
insert into review_finding (
    id, review_run_id, pr_revision_id, fingerprint, rule_id, severity,
    file_path, line_start, state, created_at
) values (
    '00000000-0000-4000-8000-0000000d1930', '00000000-0000-4000-8000-0000000d1903',
    '00000000-0000-4000-8000-0000000d1902', repeat('2', 64), 'dp19-seed-rule', 'INFO',
    'src/Main.java', 1, 'PUBLISHED', '2026-08-01T00:08:00Z'
);

insert into execution_event (
    event_id, review_run_id, pr_revision_id, step_id, attempt_id,
    event_type, schema_version, correlation_id, producer, payload, occurred_at
) values (
    '00000000-0000-4000-8000-0000000d1940', '00000000-0000-4000-8000-0000000d1903',
    '00000000-0000-4000-8000-0000000d1902', null, null,
    'RUN_CREATED', 1, '00000000-0000-4000-8000-0000000d1903', 'dp19-seed',
    '{}'::jsonb, '2026-08-01T00:02:00Z'
);

commit;
