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
}
