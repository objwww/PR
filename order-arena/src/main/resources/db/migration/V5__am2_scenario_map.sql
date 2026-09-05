-- ============================================================================
-- V5 —— AM2 arena 域：场景地图 oa_scenario_map（M2-24）
--   设计依据：C-6（指纹算法与 Alertmanager 完全一致 = FNV-1a 64 over 排序标签；
--   不做时间窗猜测——告警 fingerprint 单键直配 scenario）。
--
-- 版本行语义（append + CAS，禁原地改）：
--   激活时写 mapping_version=1（fingerprint + 冻结标签集 + rule_digest）；
--   事件回填（incident 绑定）= 新版本行（version+1），CAS 语义由应用保证：
--   旧 generation 的迟到事件回填被拒（不串场）。
-- ============================================================================

create table arena.oa_scenario_map (
    id                  uuid primary key,
    scenario_id         text not null,
    mapping_version     integer not null check (mapping_version >= 1),
    alert_fingerprint   char(16) not null,      -- AM 兼容 FNV-1a 64，十六进制 16 位
    alert_labels        jsonb not null,         -- 冻结标签集（生成指纹的原集）
    rule_digest         char(64) not null,      -- 期望命中的规则表达式摘要
    incident_id         text,                   -- 事件回填面（AM/事件侧身份）
    incident_generation bigint,
    run_id              text,
    report_id           text,
    created_at          timestamptz not null default now(),

    constraint ck_map_fp check (alert_fingerprint ~ '^[0-9a-f]{16}$')
);

-- 版本行唯一（CAS 追加的锚）；指纹+版本唯一（同代内指纹不串场景）
create unique index uq_scenario_map_version on arena.oa_scenario_map(scenario_id, mapping_version);
create unique index uq_scenario_map_fp on arena.oa_scenario_map(alert_fingerprint, mapping_version);

-- 指纹直配扫描面（评测侧按 fingerprint 找场景）
create index ix_scenario_map_fp on arena.oa_scenario_map(alert_fingerprint);

-- 授权：写者 = chaos_admin_app（激活同事务写 v1 行）；eval_app 读（default 已覆盖，显式防漂移）
grant select, insert on arena.oa_scenario_map to chaos_admin_app;
grant select on arena.oa_scenario_map to eval_app;

revoke all on arena.oa_scenario_map from control_app;
revoke all on arena.oa_scenario_map from publisher_app;
revoke all on arena.oa_scenario_map from public;
