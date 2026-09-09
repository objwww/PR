package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V30（AM6 M6-01）本地静态门：Docker 不可用时也锁住 canary 证据两表的关键结构
 * 与 insert-only 授权边界（INV-AM6-5：DRILL/REPLAY 只记录不晋升的 DB 值域面）。
 * 真 PG 约束/授权行为由 Testcontainers IT 在 195 补真证据。
 * 范式沿 Am5MigrationContractTest：规范化大小写/空白后整体比对。
 */
class Am6MigrationContractTest {

    private static final Path V30 = Path.of(
            "src/main/resources/db/migration/V30__am6_canary_window_verdict.sql");

    private static String normalized() throws IOException {
        return Files.readString(V30).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v30CreatesEvidenceSampleAndWindowVerdictTables() throws IOException {
        String sql = normalized();

        // canary_evidence_sample（采集样本 append-only；run 唯一 = 一 run 一样本；
        // LIVE provenance 可追生产事实，observed 原始指标不预聚合覆盖）
        assertThat(sql)
                .contains("create table canary_evidence_sample")
                .contains("run_id uuid not null unique references rca_run(id)")
                .contains("stickiness_key text not null")
                .contains("evidence_class varchar(16) not null")
                .contains("provenance_json jsonb not null")
                .contains("observed_json jsonb not null")
                .contains("constraint ck_ces_class check (evidence_class in "
                        + "('live_canary','drill','replay'))")
                .contains("comment on table canary_evidence_sample");

        // canary_window_verdict（稳定候选身份三 digest + 比例带 + 窗序；双门明细 jsonb）
        assertThat(sql)
                .contains("create table canary_window_verdict")
                .contains("candidate_digest char(64) not null")
                .contains("rollout_policy_digest char(64) not null")
                .contains("capability_digest char(64) not null")
                .contains("from_percent int not null")
                .contains("to_percent int not null")
                .contains("window_seq int not null")
                .contains("eligible_incidents int not null default 0")
                .contains("raw_counts jsonb not null")
                .contains("strata_json jsonb not null")
                .contains("control_json jsonb not null")
                .contains("absolute_slo_json jsonb not null")
                .contains("critical_pass boolean")
                .contains("scored_json jsonb")
                .contains("evidence_refs jsonb not null default '[]'")
                .contains("constraint ck_cwv_class check (evidence_class in "
                        + "('live_canary','drill','replay'))")
                .contains("constraint ck_cwv_verdict check (verdict in "
                        + "('pass','fail','inconclusive'))")
                .contains("comment on table canary_window_verdict");
    }

    @Test
    void v30WindowIdentityIsUniquePerClassAndSeq() throws IOException {
        String sql = normalized();

        // uq_cwv_window：同窗幂等锚（重评不重记）；evidence_class 入键 = 同窗位
        // 不同分级各占一行（INV-AM6-5：DRILL/REPLAY 证据留痕但绝不混入 LIVE 链）
        assertThat(sql).contains("constraint uq_cwv_window unique")
                .contains("unique (rollout_id,candidate_digest,rollout_policy_digest,"
                        + "capability_digest,")
                .contains("from_percent,to_percent,window_seq,evidence_class)");
    }

    @Test
    void v30GrantsInsertOnlyToControlAppWithSequenceUsage() throws IOException {
        String sql = normalized();

        // 授权纪律同 V25/V24：证据表只 select,insert；BA-42①（195 真 PG 实证）：
        // append 走 bigserial 默认值需序列 USAGE——漏授即生产写路径 permission denied
        assertThat(sql)
                .contains("grant select, insert on canary_evidence_sample to control_app")
                .contains("revoke update, delete on canary_evidence_sample from control_app")
                .contains("grant usage on sequence canary_evidence_sample_id_seq to control_app")
                .contains("grant select, insert on canary_window_verdict to control_app")
                .contains("revoke update, delete on canary_window_verdict from control_app")
                .contains("grant usage on sequence canary_window_verdict_id_seq to control_app")
                .contains("revoke all on canary_evidence_sample"
                        + " from publisher_app, notify_app, eval_app, public")
                .contains("revoke all on canary_window_verdict"
                        + " from publisher_app, notify_app, eval_app, public")
                .doesNotContainPattern("grant [a-z ,]*update on canary_window_verdict")
                .doesNotContainPattern("grant [a-z ,]*delete on canary_window_verdict");
    }

    @Test
    void v30DoesNotTouchActiveRunIndex() throws IOException {
        String sql = normalized();

        // 对账（落码方案 v1.1r1）：BA-40 已由 V25 就地修复关闭（REPORTING 在集、
        // engine 粒度），V30 不再承担索引重建——迁移重入/回放不得重复 drop/create
        assertThat(sql)
                .doesNotContain("uq_rca_run_active_incident")
                .doesNotContain("drop index");
    }

    // ---------------- V32（M6-02 engine_comparison；原 V31 号位——BA-53 顺延） ----------------

    private static String normalizedV32() throws IOException {
        return Files.readString(Path.of(
                        "src/main/resources/db/migration/V32__am6_engine_comparison.sql"))
                .toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v32CreatesEngineComparisonWithPairIdempotencyAnchor() throws IOException {
        String sql = normalizedV32();

        // 观察面结论表：native run + comparison_key（两侧身份+snapshot+候选 digest）
        // 幂等锚；shadow_exec_ref 审计引用（M6-02 am4-shadow / M6-05 shadow work）；
        // 无 GT 只记 disagreement 不判对错（架构 :736/739）
        assertThat(sql)
                .contains("create table engine_comparison")
                .contains("native_run_id uuid not null")
                .contains("comparison_key char(64) not null")
                .contains("shadow_exec_ref text not null")
                .contains("snapshot_digest char(64) not null")
                .contains("holmes_outcome jsonb")
                .contains("native_outcome jsonb")
                .contains("disagree_flags jsonb not null default '[]'")
                .contains("noise_baseline jsonb")
                .contains("cost_compare jsonb")
                .contains("constraint uq_ec_pair unique (native_run_id, comparison_key)")
                // native_run_id 无 FK：对照行是观察面结论，不绑 run 生命周期
                .doesNotContain("references rca_run");
    }

    @Test
    void v32GrantsInsertOnlyToControlAppWithSequenceUsage() throws IOException {
        String sql = normalizedV32();

        // 授权纪律同 V25/V30：证据表只 select,insert；BA-42① 序列 USAGE 面同律
        assertThat(sql)
                .contains("grant select, insert on engine_comparison to control_app")
                .contains("revoke update, delete on engine_comparison from control_app")
                .contains("grant usage on sequence engine_comparison_id_seq to control_app")
                .contains("revoke all on engine_comparison"
                        + " from publisher_app, notify_app, eval_app, public")
                .doesNotContainPattern("grant [a-z ,]*update on engine_comparison")
                .doesNotContainPattern("grant [a-z ,]*delete on engine_comparison");
    }

    // ---------------- V34（M6-05 Holmes shadow 持久工作面；doc 记 V33，C-68 顺延） ----------------

    private static String normalizedV34() throws IOException {
        return Files.readString(Path.of(
                        "src/main/resources/db/migration/V34__am6_engine_shadow_work.sql"))
                .toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v34CreatesShadowWorkFaceWithLeaseAndBoundedRetryColumns() throws IOException {
        String sql = normalizedV34();

        // C-65 持久工作面：确定性 shadow_key 唯一（重入队幂等）+ 租约三元
        // （owner/until/epoch CAS 基准）+ 有界重试（attempts/max_attempts）+
        // 封闭 kind/state 值域；native_run_id 真 FK（执行锚完整性）
        assertThat(sql)
                .contains("create table holmes_shadow_work")
                .contains("shadow_key text not null unique")
                .contains("check (kind in ('comparison', 'calibration'))")
                .contains("native_run_id uuid not null references rca_run(id)")
                .contains("incident_id uuid not null references incident(id)")
                .contains("snapshot_digest char(64) not null")
                .contains("check (state in ('queued', 'leased', 'succeeded', 'failed', 'exhausted'))")
                .contains("attempts integer not null default 0")
                .contains("max_attempts integer not null default 3")
                .contains("lease_owner text")
                .contains("lease_until timestamptz")
                .contains("lease_epoch integer not null default 0")
                .contains("tokens_spent integer")
                .contains("constraint ck_hsw_generation check (generation >= 0)")
                .contains("constraint ck_hsw_attempts check (attempts >= 0)")
                .contains("create index ix_hsw_claim on holmes_shadow_work (state, created_at)")
                .contains("comment on table holmes_shadow_work");
    }

    @Test
    void v34GrantsSelectInsertUpdateButNeverDeleteToControlApp() throws IOException {
        String sql = normalizedV34();

        // 与 V33 append-only 的授权差异（有意，迁移头注释声明）：租约工作面的
        // state/lease/attempts UPDATE 是语义的一部分（认领/CAS/有界重试）；
        // DELETE 仍拒（工作历史不可抹）；BA-42① 序列 USAGE 同律
        assertThat(sql)
                .contains("grant select, insert, update on holmes_shadow_work to control_app")
                .contains("grant usage on sequence holmes_shadow_work_id_seq to control_app")
                .contains("revoke delete on holmes_shadow_work from control_app")
                .contains("revoke all on holmes_shadow_work"
                        + " from publisher_app, notify_app, eval_app, public")
                .doesNotContainPattern("grant [a-z ,]*delete on holmes_shadow_work");
    }

    // ---------------- V35（M6-07 BA-60：HOLMES 意愿决策 run_id 摘 NOT NULL） ----------------

    @Test
    void v35DropsRunIdNotNullKeepingDeferredFkUntouched() throws IOException {
        String sql = Files.readString(Path.of(
                        "src/main/resources/db/migration/V35__m607_holmes_wish_decision_run_id_nullable.sql"))
                .toLowerCase().replaceAll("\\s+", " ");

        // 退场后 HOLMES 意愿/止损决策照记不铸 run（C-77）——预生成 runId 成幽灵引用
        // 时 V31 deferred FK 提交点 23503（BA-60）。只摘 NOT NULL；FK 约束本体与
        // DEFERRABLE 语义 V31 原样（NULL 不经 FK 检查，NATIVE 出路完整性不松）
        assertThat(sql)
                .contains("alter table canary_route_decision alter column run_id drop not null")
                .doesNotContain("drop constraint")
                .doesNotContain("add constraint");
    }
}
