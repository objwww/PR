package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresReleaseQualificationRepository;
import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-02 发布资格真 PG 组件测试（V61；增强线方案 §8.2 评测证明契约的激活门面）：
 * <ul>
 *   <li>资格门数据面：无未撤销 PASS 证明 → 激活拒绝零指针；FAIL+MATCHED 可记录但
 *       永不合格（E05/S09——MATCHED 不代替质量 PASS）；</li>
 *   <li>P07 串行化：资格行 FOR UPDATE 在手时激活阻塞，释放后才落位——激活与撤销
 *       同资格行互斥（语句级 EXISTS 是第二道，锁是第一道）；</li>
 *   <li>撤销即拒：revoke 后 activateQualified 恒 false（不追溯降级在位指针，§九）；
 *       重授 PASS 解锁；</li>
 *   <li>FK/CHECK/授权面：candidate 必须已发布（23503）；撤销三件套 DB 面一致
 *       （ck_release_qualification_revoke）；control_app 无 delete。</li>
 * </ul>
 * 本机无 Docker 自动跳过（真证据待 195 窗统一补——三线串行规约 §6.1）。
 */
class PostgresReleaseActivationIT extends PostgresITBase {

    private ConfigBundleRepository bundles;
    private ConfigBundleService service;

    @BeforeEach
    void setUp() {
        // V24 两表不在基座 TRUNCATE 清单（AM5 表惯例自清）；pointer 先删再重种未激活行
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();

        bundles = new PostgresConfigBundleRepository(controlDataSource());
        service = new ConfigBundleService(bundles,
                new com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository(
                        controlDataSource()),
                new PostgresReleaseQualificationRepository(controlDataSource()));
    }

    private static Map<String, Object> content(String tag) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", tag);
        return content;
    }

    private Digest publish(String tag) {
        return service.publish(content(tag), "it-op").bundleDigest();
    }

    // -------------------------------------------------- 资格门数据面

    @Test
    @DisplayName("门：无证明 → 服务 ISE + 仓储恒 false 零指针；授予 PASS 后激活落位")
    void gateRequiresUnrevokedPassProof() {
        Digest d1 = publish("v7");

        assertThatIllegalStateException()
                .isThrownBy(() -> service.activate(d1, 0L, "it-op"))
                .withMessageContaining("QUALIFICATION_ABSENT");
        assertThat(bundles.activateQualified(d1, 0L, "it-op", Instant.now())).isFalse();
        assertThat(bundles.activeDigest()).isEmpty();

        service.grantQualification(d1, null, "ef".repeat(32), "runner-it", "grader-it",
                "PASS", "UNKNOWN", "scope:it", "it-grader");
        assertThat(bundles.activateQualified(d1, 0L, "it-op", Instant.now())).isTrue();
        assertThat(bundles.activeDigest()).contains(d1);
    }

    @Test
    @DisplayName("E05/S09：FAIL+MATCHED 行可记录但门恒拒（MATCHED 不代替质量 PASS）")
    void failVerdictNeverQualifies() {
        Digest d1 = publish("v7");
        service.grantQualification(d1, null, "ef".repeat(32), "runner-it", "grader-it",
                "FAIL", "MATCHED", "scope:it", "it-grader");

        assertThatIllegalStateException()
                .isThrownBy(() -> service.activate(d1, 0L, "it-op"))
                .withMessageContaining("QUALITY_NOT_PASS(FAIL)");
        assertThat(bundles.activateQualified(d1, 0L, "it-op", Instant.now())).isFalse();
        assertThat(bundles.activeDigest()).isEmpty();
    }

    // -------------------------------------------------- 撤销即拒（不追溯降级）

    @Test
    @DisplayName("撤销：未激活目标 → 激活拒绝；重授 PASS 解锁；在位指针不被追溯降级")
    void revocationBlocksFutureActivationOnly() {
        Digest d1 = publish("v7");
        Digest d2 = publish("v8");
        java.util.UUID d1Proof = service.grantQualification(d1, null, "ef".repeat(32),
                "runner-it", "grader-it", "PASS", "MATCHED", "scope:it", "it-grader");
        service.grantQualification(d2, null, "ef".repeat(32), "runner-it", "grader-it",
                "PASS", "MATCHED", "scope:it", "it-grader");
        assertThat(bundles.activateQualified(d1, 0L, "it-op", Instant.now())).isTrue();

        // 撤销 d1 的证明：已在位指针不动（§九），换目标激活不受影响
        assertThat(service.revokeQualification(d1Proof, "safety-officer", "基线漂移"))
                .isTrue();
        assertThat(bundles.activeDigest()).contains(d1);
        assertThat(bundles.activateQualified(d2, 1L, "it-op", Instant.now())).isTrue();

        // 回滚到已撤销证明的 d1 → 资格门拒绝（P11：回滚同门）
        assertThatIllegalStateException()
                .isThrownBy(() -> service.rollback(d1, 2L, "it-op"))
                .withMessageContaining("QUALIFICATION_ABSENT");
    }

    // -------------------------------------------------- P07 双连接 barrier

    @Test
    @DisplayName("P07 barrier：资格行 FOR UPDATE 在手 → 激活阻塞；释放后激活才落位")
    void qualificationLockSerializesActivation() throws Exception {
        Digest d1 = publish("v7");
        service.grantQualification(d1, null, "ef".repeat(32), "runner-it", "grader-it",
                "PASS", "UNKNOWN", "scope:it", "it-grader");

        // 连接①：持有资格行锁（撤销场景下的同型互斥——撤销 UPDATE 走同一行）
        Connection holder = controlDataSource().getConnection();
        holder.setAutoCommit(false);
        java.sql.Statement lockStmt = holder.createStatement();
        java.sql.ResultSet lockRow = lockStmt.executeQuery(
                "SELECT id FROM release_qualification WHERE candidate_digest = '"
                        + d1.hex() + "' FOR UPDATE");
        org.assertj.core.api.Assertions.assertThat(lockRow.next()).isTrue();

        // 连接②：激活必须等锁（FOR UPDATE 是激活/撤销串行化的第一道）
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> activation = pool.submit(() ->
                    bundles.activateQualified(d1, 0L, "racer", Instant.now()));
            TimeUnit.MILLISECONDS.sleep(300);
            assertThat(activation.isDone())
                    .as("资格行被锁住时激活必须阻塞（P07）")
                    .isFalse();

            holder.rollback();
            assertThat(activation.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bundles.activeDigest()).contains(d1);
        } finally {
            pool.shutdownNow();
            lockRow.close();
            lockStmt.close();
            holder.close();
        }
    }

    // -------------------------------------------------- V61 FK / CHECK / 授权面

    @Test
    @DisplayName("V61 面：candidate FK（未发布即拒）；撤销三件套 DB CHECK；control_app 无 delete")
    void v61ContractFace() {
        Digest d1 = publish("v7");

        // FK：candidate 未发布 → 23503（control_app 视角 DataAccessException）
        assertThatThrownBy(() -> controlJdbc.sql("""
                        INSERT INTO release_qualification (
                            id, candidate_digest, dataset_manifest_digest,
                            runner_version, grader_version, quality_verdict, usage_status,
                            granted_scope, granted_by, granted_at)
                        VALUES (:id, :c, :ds, 'r', 'g', 'PASS', 'UNKNOWN', 's', 'by', now())
                        """)
                .param("id", java.util.UUID.randomUUID())
                .param("c", "ff".repeat(32))
                .param("ds", "ef".repeat(32)).update())
                .as("candidate FK 钉已发布面")
                .isInstanceOf(DataAccessException.class);

        Digest proofOf = publish("v8");
        service.grantQualification(proofOf, null, "ef".repeat(32), "runner-it",
                "grader-it", "PASS", "UNKNOWN", "scope:it", "it-grader");

        // CHECK：revoked_at 单独在场（缺 by/reason）→ ck_release_qualification_revoke 拒绝
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                controlJdbc.sql("UPDATE release_qualification SET revoked_at = now()").update());

        // 授权面：select/insert/update 具备（撤销=UPDATE 三列），delete 零授
        controlJdbc.sql("SELECT count(*) FROM release_qualification")
                .query(Long.class).single();
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                controlJdbc.sql("DELETE FROM release_qualification").update());

        // 他角色全零（V61 revoke all）
        assertThatExceptionOfType(DataAccessException.class).isThrownBy(() ->
                evalJdbc.sql("SELECT count(*) FROM release_qualification")
                        .query(Long.class).single());
    }
}
