package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20（AM5 M5-01）本地静态门：Docker 不可用时也锁住数据集版本两表的关键结构
 * 与 insert-only 授权边界（INV-AM5-1：历史不可覆盖）。真 PG 约束/授权行为由
 * PostgresDatasetVersionRepositoryTest（Testcontainers IT，195 补真证据）覆盖。
 * 范式沿 M7MigrationContractTest：规范化大小写/空白后整体比对。
 */
class Am5MigrationContractTest {

    private static final Path V20 = Path.of(
            "src/main/resources/db/migration/V20__am5_dataset_version.sql");

    private static final Path V21 = Path.of(
            "src/main/resources/db/migration/V21__am5_dataset_partition.sql");

    private static final Path V22 = Path.of(
            "src/main/resources/db/migration/V22__am5_golden_candidate.sql");

    private static final Path V23 = Path.of(
            "src/main/resources/db/migration/V23__am5_sampling_fingerprint.sql");

    private static final Path V24 = Path.of(
            "src/main/resources/db/migration/V24__am5_config_bundle.sql");

    private static final Path V25 = Path.of(
            "src/main/resources/db/migration/V25__am5_canary_route.sql");
    private static final Path V26 = Path.of(
            "src/main/resources/db/migration/V26__am5_operator_case.sql");
    private static final Path V27 = Path.of(
            "src/main/resources/db/migration/V27__am5_operator_command.sql");
    private static final Path V28 = Path.of(
            "src/main/resources/db/migration/V28__am5_monthly_partition.sql");
    private static final Path V29 = Path.of(
            "src/main/resources/db/migration/V29__am5_retention_archive.sql");

    private static String normalized() throws IOException {
        return normalized(V20);
    }

    private static String normalized(Path migration) throws IOException {
        return Files.readString(migration).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v20CreatesDatasetAndCaseVersionTablesWithNineSourceFields() throws IOException {
        String sql = normalized();

        assertThat(sql)
                .contains("create table dataset_version")
                // 来源九字段（方案 §3.1 v1.1 冻结清单）
                .contains("source text not null")
                .contains("name text not null")
                .contains("version text not null")
                .contains("source_uri text not null")
                .contains("license text not null")
                .contains("access_class text not null")
                .contains("content_digest char(64) not null")
                .contains("adapter_version text not null")
                .contains("imported_at timestamptz not null")
                // 分级 + 四分区归属 + 族清单摘要（落码方案 §M5-01②）
                .contains("source_class varchar(24) not null")
                .contains("partition_class varchar(16) not null")
                .contains("scenario_family_digest char(64) not null")
                .contains("check (source_class in ('private','public_benchmark'))")
                .contains("check (partition_class in ('tuning','validation','holdout','redteam'))")
                .contains("create table case_version")
                .contains("scenario_family_id text not null")
                .contains("valid_from timestamptz not null")
                .contains("valid_to timestamptz")
                .contains("source_artifact_ref text")
                .contains("payload jsonb not null")
                // 落码方案要求 comment on table
                .contains("comment on table dataset_version")
                .contains("comment on table case_version");
    }

    @Test
    void v20HistoryIsNotOverwritable() throws IOException {
        String sql = normalized();

        // insert-only：同 (name,version) 数据集版本禁重生；同 (dataset,case_key)
        // 案例版本禁覆盖（纠错 = 新 dataset_version 携带修正行）；适用期窗口合法性
        assertThat(sql)
                .contains("constraint uq_dataset_version unique (name, version)")
                .contains("constraint uq_case_version unique (dataset_version_id, case_key)")
                .contains("check (valid_to is null or valid_to > valid_from)")
                .contains("references dataset_version(id)");
    }

    @Test
    void v20GrantsInsertOnlyToEvalAppAndZeroToOtherRoles() throws IOException {
        String sql = normalized();

        // eval_app = eval-runner 独立身份：只 select+insert，无任何 UPDATE/DELETE 授权
        assertThat(sql)
                .contains("grant select, insert on dataset_version, case_version to eval_app")
                .doesNotContainPattern("grant [a-z ,]*update on dataset_version")
                .doesNotContainPattern("grant [a-z ,]*update on case_version")
                .doesNotContainPattern("grant [a-z ,]*delete on dataset_version")
                .doesNotContainPattern("grant [a-z ,]*delete on case_version")
                // 生产角色 + public 显式零权限（V10 惯例）
                .contains("revoke all on dataset_version, case_version"
                        + " from control_app, publisher_app, notify_app, public")
                // arena 域角色条件化幂等 revoke（干净 IT 库可能不存在）
                .contains("if exists (select from pg_roles where rolname = 'arena_app')")
                .contains("if exists (select from pg_roles where rolname = 'chaos_admin_app')");
    }

    @Test
    void v21DenormalizesPartitionOntoCaseVersionWithIntegrityAndCheck() throws IOException {
        String sql = normalized(V21);

        // case_version.partition_class 冗余直挂（V9 FUT-50 惯例：禁 JOIN 推导）
        // + 四值封闭（落码方案 §M5-02② 点名 ck_case_version_partition）
        // + 复合 FK 对 dataset_version(id, partition_class) 保冗余列与头表一致
        assertThat(sql)
                .contains("alter table case_version add column partition_class varchar(16) not null")
                .contains("constraint ck_case_version_partition check (partition_class in "
                        + "('tuning','validation','holdout','redteam'))")
                .contains("constraint uq_dataset_version_partition unique (id, partition_class)")
                .contains("constraint fk_case_version_dataset_partition foreign key "
                        + "(dataset_version_id, partition_class) references "
                        + "dataset_version (id, partition_class)");
    }

    @Test
    void v21RegistryTableEnforcesWholeFamilySinglePartition() throws IOException {
        String sql = normalized(V21);

        // family 单分区登记面（insert-only）：PK(scenario_family_id) = 同族二次登记
        // 不同分区 DuplicateKey 拒绝——落码方案 §M5-02② unique(scenario_family_id,
        // partition_class) 直排 case_version 行面不可实施（同族多 Case 是设计常态 +
        // 纠错=新 dataset_version 修正行），登记面表达成同一不变量（决策见 PROGRESS C-6）
        assertThat(sql)
                .contains("create table case_family_partition")
                .contains("scenario_family_id text primary key")
                .contains("constraint ck_case_family_partition_class check (partition_class in "
                        + "('tuning','validation','holdout','redteam'))")
                .contains("grant select, insert on case_family_partition to eval_app")
                .doesNotContainPattern("grant [a-z ,]*update on case_family_partition")
                .doesNotContainPattern("grant [a-z ,]*delete on case_family_partition")
                .contains("revoke all on case_family_partition"
                        + " from control_app, publisher_app, notify_app, public");
    }

    @Test
    void v21RlsIsolatesHoldoutAndMapsFourPartitionRoles() throws IOException {
        String sql = normalized(V21);

        // RLS + 分区角色（落码方案 §M5-02② 落码建议）：case_version 启用行级安全
        assertThat(sql)
                .contains("alter table case_version enable row level security")
                // 四分区角色（V9 条件化幂等建角色同构；NOLOGIN 纯授权目标）
                .contains("eval_tuning_ro").contains("eval_validation_ro")
                .contains("eval_holdout_gate").contains("eval_redteam_gate")
                // 四角色各见单分区
                .contains("using (partition_class = 'tuning')")
                .contains("using (partition_class = 'validation')")
                .contains("using (partition_class = 'holdout')")
                .contains("using (partition_class = 'redteam')")
                // 评分身份 eval_app：封存门前不见 HOLDOUT（select+insert 同谓词——
                // RLS 对其写路径同样生效，无策略即默认拒绝）
                .contains("for insert to eval_app with check (partition_class <> 'holdout')")
                .contains("for select to eval_app using (partition_class <> 'holdout')")
                // 四分区角色只读
                .contains("grant select on case_version to eval_tuning_ro, "
                        + "eval_validation_ro, eval_holdout_gate, eval_redteam_gate");
    }

    @Test
    void v21PublicBenchmarkCanNeverClaimHoldoutPartition() throws IOException {
        String sql = normalized(V21);

        // INV-AM5-1 DB 兜底：公共 benchmark 不冒充私有 HOLDOUT（插入触发器硬拒绝；
        // dataset_version insert-only，无 UPDATE 路径故只挂 before insert）
        assertThat(sql)
                .contains("create trigger trg_dataset_benchmark_not_holdout")
                .contains("before insert on dataset_version")
                .contains("public_benchmark")
                .contains("'holdout'");
    }

    @Test
    void v22GoldenCandidateEncodesDualReviewAndStateMachine() throws IOException {
        String sql = normalized(V22);

        // INV-AM5-2：同人不能双签 DB 兜底 + 终态结论必须双签齐全 + 状态机值域
        assertThat(sql)
                .contains("create table golden_candidate")
                .contains("constraint ck_golden_dual_review check (reviewer_a is null"
                        + " or reviewer_b is null or reviewer_a <> reviewer_b)")
                .contains("check ((state in ('published','rejected')")
                .contains("or state in ('draft','review','withdrawn'))")
                .contains("check (state in ('draft','review','published','rejected','withdrawn'))")
                .contains("references case_version(id)")
                .contains("revision bigint not null default 0");
    }

    @Test
    void v22ReviewEventIsAppendOnlyAndIdempotencyKeyed() throws IOException {
        String sql = normalized(V22);

        assertThat(sql)
                .contains("create table golden_review_event")
                .contains("constraint uq_golden_review_event_idem unique (idempotency_key)")
                .contains("check (action in ('proposed','submitted','published','rejected','withdrawn'))")
                // eval_app 读写候选：候选列级 UPDATE 只开口迁移所需列（V7 惯例），
                // 事件表 append-only 只授 select,insert
                .contains("grant update ( state, reviewer_a, reviewer_b, revision, updated_at )"
                        + " on golden_candidate to eval_app")
                .contains("grant select, insert on golden_review_event to eval_app")
                .doesNotContainPattern("grant [a-z ,]*update on golden_review_event")
                .doesNotContainPattern("grant [a-z ,]*delete on golden_review_event")
                .contains("revoke all on golden_candidate, golden_review_event"
                        + " from control_app, publisher_app, notify_app, public");
    }

    @Test
    void v23SamplingFingerprintLandsOnAttemptWithKeyContract() throws IOException {
        String sql = normalized(V23);

        // 落码方案 §M5-04② 方案甲：rca_attempt 直挂 sampling_fingerprint jsonb
        //（两态/身份/trial 键集 DB 兜底；字段级完整性由域面 isGateEligible 把门，
        // jsonb 键存在性检查对嵌套 null 值不过问——诚实留空与缺键在 DB 面不分家）
        assertThat(sql)
                .contains("alter table rca_attempt add column sampling_fingerprint jsonb")
                .contains("constraint ck_rca_attempt_fingerprint_keys check (sampling_fingerprint is null")
                .contains("sampling_fingerprint ? 'requested'")
                .contains("sampling_fingerprint ? 'effective'")
                .contains("sampling_fingerprint ? 'provider_fingerprint'")
                .contains("sampling_fingerprint ? 'model'")
                .contains("sampling_fingerprint ? 'trial_no'")
                .contains("comment on column rca_attempt.sampling_fingerprint");
    }

    @Test
    void v24ConfigBundleIsImmutableWithDigestIdempotencyAnchor() throws IOException {
        String sql = normalized(V24);

        // INV-AM5-5 DB 面：bundle 行不可变（digest 唯一 = 发布幂等锚；revision>0；
        // content 只锁 JSON 对象形状——密钥检测归发布面键名扫描 fail-closed）
        assertThat(sql)
                .contains("create table config_bundle")
                .contains("bundle_digest char(64) not null")
                .contains("check (revision > 0)")
                .contains("check (jsonb_typeof(content) = 'object')")
                .contains("constraint uq_config_bundle_digest unique (bundle_digest)")
                .contains("comment on table config_bundle");
    }

    @Test
    void v24ActivePointerIsSingleRowCasFaceSeededUnactivated() throws IOException {
        String sql = normalized(V24);

        // 单行 pointer（id=1 唯一行）+ 未激活种子行 + 半激活态禁约束：
        // digest 置位时 activated_at/by 必须同行齐备（回滚/激活 = CAS update 无第三态）
        assertThat(sql)
                .contains("create table config_bundle_active")
                .contains("check (id = 1)")
                .contains("references config_bundle (bundle_digest)")
                .contains("constraint ck_config_bundle_active_unactivated")
                .contains("check (bundle_digest is not null or (activated_at is null"
                        + " and activated_by is null))")
                .contains("insert into config_bundle_active (id) values (1)");
    }

    @Test
    void v24GrantsFreezeImmutableHistoryAndPointer() throws IOException {
        String sql = normalized(V24);

        // 授权面：bundle 只 select,insert（历史行零 UPDATE/DELETE 路径，INV-AM5-5）；
        // pointer 允许 update（激活/回滚唯一写面）禁 delete；eval/publisher/notify 全零
        assertThat(sql)
                .contains("grant select, insert on config_bundle to control_app")
                .contains("revoke update, delete on config_bundle from control_app")
                .contains("grant select, update on config_bundle_active to control_app")
                .contains("revoke delete on config_bundle_active from control_app")
                .contains("revoke all on config_bundle, config_bundle_active"
                        + " from publisher_app, notify_app, eval_app, public");
    }

    @Test
    void v25RunRoutingColumnsPinEngineAndDigestAtCastTime() throws IOException {
        String sql = normalized(V25);

        // 路由四列（落码方案 §M5-10②）：engine 默认 HOLMES 存量行为零变；
        // config_digest = Run 启动固定快照（老 Run 固定旧 digest）；NATIVE 必带 digest
        assertThat(sql)
                .contains("alter table rca_run add column engine varchar(16) not null default 'holmes'")
                .contains("alter table rca_run add column config_digest char(64)")
                .contains("alter table rca_run add column stickiness_key text")
                .contains("alter table rca_run add column canary_bucket integer")
                .contains("constraint ck_rca_run_engine check (engine in ('holmes','native'))")
                .contains("constraint ck_rca_run_native_digest")
                .contains("check (engine <> 'native' or config_digest is not null)");
    }

    @Test
    void v25ActiveIndexWidensToEngineGranularityAndDecisionTableIsAppendOnly() throws IOException {
        String sql = normalized(V25);

        // 唯一活跃索引 (incident_id, engine)——Shadow（默认 HOLMES）行为不变，
        // NATIVE 候选获得独立槽位（C-2 矛盾消解面）；决策表 append-only 冻结值域
        assertThat(sql)
                .contains("drop index uq_rca_run_active_incident")
                .contains("create unique index uq_rca_run_active_incident")
                .contains("on rca_run(incident_id, engine) where state in ('queued','running')")
                .contains("create table canary_route_decision")
                .contains("run_id uuid not null references rca_run (id)")
                .contains("check (percent between 0 and 100)")
                .contains("'no_active_bundle','canary_disabled','no_stickiness_key'")
                .contains("'whitelisted','bucketed_native','bucketed_holmes'")
                .contains("'native_deferred','blast_radius_stopped'")
                .contains("grant select, insert on canary_route_decision to control_app")
                .contains("revoke update, delete on canary_route_decision from control_app")
                // BA-42①（195 真 PG 实证）：append 走 bigserial 默认值需序列 USAGE，
                // 表授权不覆盖序列面——漏授即生产写路径 permission denied
                .contains("grant usage on sequence canary_route_decision_id_seq to control_app")
                .contains("revoke all on canary_route_decision"
                        + " from publisher_app, notify_app, eval_app, public");
    }

    @Test
    void v26OperatorCasePinsMergeKeySnapshotFreezeAndAbsorbingResolved() throws IOException {
        String sql = normalized(V26);

        // 幂等合并键 + 快照冻结 + N≥1 证据（落码方案 §M5-11②）；RESOLVED 吸收态由状态机
        // 管辖（DB 只锁值域）；rev CAS 列
        assertThat(sql)
                .contains("create table operator_case")
                .contains("constraint uq_operator_case_tenant_fingerprint unique (tenant, fingerprint)")
                .contains("snapshot_digest char(64)")
                .contains("observed_generation integer not null default 0")
                .contains("check (jsonb_typeof(evidence_refs) = 'array'")
                .contains("jsonb_array_length(evidence_refs) >= 1")
                .contains("status varchar(16) not null default 'open'")
                .contains("check (status in ('open','acked','resolved'))")
                .contains("revision bigint not null default 1");
    }

    @Test
    void v26NotifyOutboxCaseAssociationColumnAndGrants() throws IOException {
        String sql = normalized(V26);

        // AM7 IN_APP 渠道预留缝：只落关联列不写通知行（落码方案 §M5-11① 原文）；
        // 授权面：命令面 CAS 需 update，delete 零开口
        assertThat(sql)
                .contains("alter table notify_outbox add column case_id uuid references operator_case(id)")
                .contains("grant select, insert, update on operator_case to control_app")
                .contains("revoke delete on operator_case from control_app")
                .contains("revoke all on operator_case"
                        + " from publisher_app, notify_app, eval_app, public");
    }

    @Test
    void v27OperatorCommandPinsIdempotencyAnchorAndOneWayState() throws IOException {
        String sql = normalized(V27);

        // 幂等锚 + 命令值域 + 状态单向推进（落码方案 §M5-14②）；
        // expected_revision 锚 rca_run.last_event_seq（C-19① 修订锚裁定）
        assertThat(sql)
                .contains("create table operator_command")
                .contains("run_id uuid not null references rca_run(id)")
                .contains("check (command_type in ('cancel','hint','feedback'))")
                .contains("constraint uq_operator_command_idem unique "
                        + "(run_id, command_type, idempotency_key)")
                .contains("expected_revision bigint not null")
                .contains("state varchar(16) not null default 'persisted'")
                .contains("check (state in ('persisted','applied','rejected_stale','rejected_forbidden'))")
                .contains("check (jsonb_typeof(payload) = 'object')")
                .contains("comment on table operator_command");
    }

    @Test
    void v27GrantsTerminalColumnUpdateOnlyAndZeroDelete() throws IOException {
        String sql = normalized(V27);

        // 授权面：账本行 insert 后正文不可改；终态推进只开口 state/applied_at 两列
        //（V9 列级授权同构）；delete 零开口
        assertThat(sql)
                .contains("grant select, insert on operator_command to control_app")
                .contains("grant update (state, applied_at) on operator_command to control_app")
                .contains("revoke delete on operator_command from control_app")
                .contains("revoke all on operator_command"
                        + " from publisher_app, notify_app, eval_app, public");
    }

    @Test
    void v28PartitionsRcaEventByMonthWithPartitionKeyInEveryUniqueConstraint() throws IOException {
        String sql = normalized(V28);

        // E-17 坑 pin（落码方案 §M5-18② 原文）：分区表唯一约束必须含分区键
        // created_at——(run_id,seq) 无洞单调由 M4-10 计数器行锁保证（应用面不变量），
        // 约束放宽只为满足 PG 分区规则，不放松防重入语义
        assertThat(sql)
                .contains("partition by range (created_at)")
                .contains("add constraint pk_rca_event primary key (id, created_at)")
                .contains("add constraint uq_rca_event_run_seq unique (run_id, seq, created_at)")
                .contains("add constraint uq_rca_event_run_event_id unique"
                        + " (run_id, event_id, created_at)")
                // default 分区兜底（边界外写入永不因缺分区失败）
                .contains("create table rca_event_default partition of"
                        + " rca_event_partitioned default")
                // 换身序：视图按 oid 绑定旧表——先拆后建；授权随建重授（LIKE 不拷特权）
                .contains("drop view rca_agent_event")
                .contains("drop table rca_event")
                .contains("alter table rca_event_partitioned rename to rca_event")
                .contains("grant select, insert on rca_event to control_app")
                .contains("grant select on rca_agent_event to control_app");

        // BA-40（195 真 PG 42P07 实证）：PK/UNIQUE 约束的索引名是 schema 全局关系名，
        // 旧表 rca_event 同名索引（pk_rca_event 等）在场时 add constraint 即撞名——
        // 约束/索引必须排在同名换身（旧表 drop）之后；顺序回归即红
        assertThat(sql.indexOf("alter table rca_event_partitioned rename to rca_event"))
                .as("换身先于约束创建（索引名 schema 全局唯一）")
                .isLessThan(sql.indexOf("add constraint pk_rca_event"));
    }

    @Test
    void v29PinsInsertOnlyPolicyChainHoldReleaseSingleColumnAndArchiveOnceFence() throws IOException {
        String sql = normalized(V29);

        // 保留域三表（落码方案 §M5-18②）：策略链 insert-only（无 update/delete 开口）；
        // hold 唯一可变面 = released_at 单列；manifest state 单列推进 + 归档恰一次栅栏
        assertThat(sql)
                .contains("create table retention_policy")
                .contains("create table legal_hold")
                .contains("create table archive_manifest")
                .contains("released_at timestamptz")
                .contains("constraint ck_archive_manifest_state")
                .contains("check (state in ('exported', 'verified', 'archived'))")
                .contains("constraint uq_archive_manifest_partition unique (partition_name)")
                .contains("grant select, insert on retention_policy to control_app")
                .contains("revoke update, delete on retention_policy from control_app")
                .contains("grant update (released_at) on legal_hold to control_app")
                .contains("revoke delete on legal_hold from control_app")
                .contains("grant update (state) on archive_manifest to control_app")
                .contains("revoke delete on archive_manifest from control_app");

        // BA-42③（195 真 PG 实证：DETACH=ALTER TABLE 需表主，control_app 无主身份
        // → 归档工波单向卡死 FAILED_DETACH）：提权收口 = security definer 函数，
        // 动作面钉死「仅 rca_event 族分区摘离」+ 白名单 + public 零开口
        assertThat(sql)
                .contains("create function pr_archive_detach_partition(p_partition text)")
                .contains("security definer")
                .contains("p.relname = 'rca_event'")
                .contains("detach partition")
                .contains("revoke all on function pr_archive_detach_partition(text) from public")
                .contains("grant execute on function pr_archive_detach_partition(text)"
                        + " to control_app");
    }
}
