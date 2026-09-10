package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.infrastructure.tool.ChangeQueryExecutor;
import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * EX-B1 change_event 真实变更源（真 PG 面，195 官方 verify 官方证据）：
 * ①V40 schema/写读角色分离授权矩阵 ②激活/回滚事实与 pointer CAS 同事务
 * （幂等重放零事件、CAS 败者零事件、ROLLBACK 携 rollback_of）③change.query
 * 真查（窗幅/allowlist/NO_DATA/limit 200 截断）④部署写路径 deploy_app + 幂等锚。
 * 本机无 Docker 自动跳过（惯例）。
 */
class ExB1ChangeEventIT extends PostgresITBase {

    private ConfigBundleService service;
    private PostgresConfigBundleRepository repo;

    @BeforeEach
    void setUp() {
        // V24 两表不在基座 TRUNCATE 清单（AM5 惯例自清）；change_event 在清单（V40）
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();

        repo = new PostgresConfigBundleRepository(controlDataSource());
        service = new ConfigBundleService(repo);
    }

    private static Map<String, Object> content(String promptVersion) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", promptVersion);
        return content;
    }

    // -------------------------------------------------- ① schema 与授权矩阵

    @Test
    @DisplayName("V40 面：表/唯一锚/索引在；control_app 只 select+insert；deploy_app 只 insert 且 NOLOGIN")
    void v40SchemaAndSplitAuthorizationFace() {
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                         WHERE table_name = 'change_event'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM information_schema.table_constraints
                         WHERE constraint_name = 'uq_change_event_source_deploy'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(adminJdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                         WHERE indexname = 'idx_change_event_service_window'
                        """).query(Long.class).single()).isEqualTo(1L);

        // 写/读角色分离（评审 B1）：control_app = 激活事实 insert + 工具读，永无改删
        assertThat(priv("control_app", "SELECT")).isTrue();
        assertThat(priv("control_app", "INSERT")).isTrue();
        assertThat(priv("control_app", "UPDATE")).isFalse();
        assertThat(priv("control_app", "DELETE")).isFalse();

        // deploy_app = 部署脚本写路径（insert-only，不可读改删；NOLOGIN = 无凭证直连）
        assertThat(priv("deploy_app", "INSERT")).isTrue();
        assertThat(priv("deploy_app", "SELECT")).isFalse();
        assertThat(priv("deploy_app", "UPDATE")).isFalse();
        assertThat(priv("deploy_app", "DELETE")).isFalse();
        assertThat(adminJdbc.sql(
                        "SELECT rolcanlogin FROM pg_roles WHERE rolname = 'deploy_app'")
                .query(Boolean.class).single()).isFalse();

        // 其余应用角色全禁（V24 惯例 revoke all）
        assertThat(priv("publisher_app", "SELECT")).isFalse();
        assertThat(priv("notify_app", "INSERT")).isFalse();
        assertThat(priv("eval_app", "SELECT")).isFalse();
    }

    /** has_table_privilege 返回布尔（B-26：information_schema 才是 YES/NO 字符串面） */
    private static boolean priv(String role, String action) {
        return adminJdbc.sql("SELECT has_table_privilege(:r, 'change_event', :a)")
                .param("r", role).param("a", action)
                .query(Boolean.class).single();
    }

    // -------------------------------------------------- ② 同事务激活事实

    @Test
    @DisplayName("激活/重放/败者/回滚四边界：仅 moved 指针落事实；ROLLBACK 携 rollback_of")
    void activationFactsFollowMovedPointerOnly() {
        Digest d1 = service.publish(content("v7"), "it-op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "it-op").bundleDigest();

        assertThat(service.activate(d1, "it-op").moved()).isTrue();
        assertThat(count("change_event")).isEqualTo(1L);

        // 幂等重放（重复激活当前）：零新事件（评审 B1 裁定）
        assertThat(service.activate(d1, "it-op").moved()).isFalse();
        assertThat(count("change_event")).isEqualTo(1L);

        // CAS 败者（expected 漂移）：5 参事务内零 INSERT
        assertThat(repo.activate(d2, Digest.sha256Of("stale"), "racer",
                Instant.now(), new ConfigBundleRepository.ActivationFact(
                        "ACTIVATE", "control-app", "production", null))).isFalse();
        assertThat(count("change_event")).isEqualTo(1L);

        // 换目标激活 + 回滚：各自一新事实；ROLLBACK 行 rollback_of = 回滚前生效 digest
        assertThat(service.activate(d2, "it-op").moved()).isTrue();
        assertThat(service.rollback(d1, "it-op").moved()).isTrue();
        assertThat(count("change_event")).isEqualTo(3L);

        // 顺序无关断言（时间戳可能并列，deploy_id 是随机 UUID 不可作序锚）
        Map<String, String> rollbackRow = rowByAction("ROLLBACK");
        assertThat(rollbackRow.get("config_digest")).isEqualTo(d1.hex());
        assertThat(rollbackRow.get("rollback_of")).isEqualTo(d2.hex());
        assertThat(rollbackRow.get("status")).isEqualTo("SUCCEEDED");

        Map<String, String> activateD1 = activateByDigest(d1);
        assertThat(activateD1.get("source")).isEqualTo("config_activation");
        assertThat(activateD1.get("action")).isEqualTo("ACTIVATE");
        assertThat(activateD1.get("service")).isEqualTo("control-app");
        assertThat(activateD1.get("environment")).isEqualTo("production");
        assertThat(activateD1.get("actor")).isEqualTo("it-op");
        assertThat(activateD1.get("rollback_of")).isNull();
        assertThat(activateByDigest(d2).get("rollback_of")).isNull();
    }

    /** 行投影：source/action/service/environment/config_digest/actor/rollback_of/status 八件 */
    private Map<String, String> rowByAction(String action) {
        return adminJdbc.sql("""
                        SELECT source, action, service, environment, config_digest,
                               actor, rollback_of, status
                          FROM change_event WHERE action = :action
                        """).param("action", action)
                .query((rs, i) -> row(rs))
                .single();
    }

    private Map<String, String> activateByDigest(Digest digest) {
        return adminJdbc.sql("""
                        SELECT source, action, service, environment, config_digest,
                               actor, rollback_of, status
                          FROM change_event
                         WHERE action = 'ACTIVATE' AND config_digest = :digest
                        """).param("digest", digest.hex())
                .query((rs, i) -> row(rs))
                .single();
    }

    /** 八件行投影（rollback_of 真可空：LinkedHashMap 落空位——B-27：Map.of 拒空值逼出
     * String.valueOf 伪影，把 DB 真 NULL 读成字符串 "null"，isNull 永假） */
    private static Map<String, String> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("source", rs.getString(1));
        row.put("action", rs.getString(2));
        row.put("service", rs.getString(3));
        row.put("environment", rs.getString(4));
        row.put("config_digest", rs.getString(5));
        row.put("actor", rs.getString(6));
        row.put("rollback_of", rs.getString(7));
        row.put("status", rs.getString(8));
        return row;
    }

    // -------------------------------------------------- ③ change.query 真查

    @Test
    @DisplayName("executor 真查 change_event：窗内行返回；NO_DATA/宽窗/allowlist 拒；201 行截 200")
    void changeQueryExecutorServesRealRowsWithGuards() throws Exception {
        seedDeploymentRow("it-deploy-1", Instant.now().minusSeconds(60), "SUCCEEDED");
        seedDeploymentRow("it-deploy-2", Instant.now().minusSeconds(30), "FAILED");

        ChangeQueryExecutor executor = new ChangeQueryExecutor(controlJdbc, Set.of("control-app"));

        // 窗内两行返回，响应形状 = Agent 统一解析面
        Instant since = Instant.now().minusSeconds(120);
        Instant until = Instant.now();
        byte[] bytes = executor.execute(new ToolExecutor.ToolExecution(
                Map.of("since", since.toString(), "until", until.toString()),
                System.currentTimeMillis() + 4_000, 65_536));
        Map<?, ?> payload = new ObjectMapper().readValue(bytes, Map.class);
        assertThat(payload.get("status")).isEqualTo("success");
        Map<?, ?> data = (Map<?, ?>) payload.get("data");
        assertThat(data.get("truncated")).isEqualTo(Boolean.FALSE);
        assertThat((List<?>) data.get("result")).hasSize(2);

        // 空窗 = 模型可见 NO_DATA（正常空结果，非故障）
        ToolModelVisibleException noData = catchThrowableOfType(
                () -> executor.execute(new ToolExecutor.ToolExecution(
                        Map.of("since", Instant.now().plusSeconds(3_600).toString(),
                                "until", Instant.now().plusSeconds(3_900).toString()),
                        System.currentTimeMillis() + 4_000, 65_536)),
                ToolModelVisibleException.class);
        assertThat(noData.reason()).isEqualTo(ToolModelVisibleReason.NO_DATA);

        // 窗幅 >900s = 控制面 INVALID_ARGS；service 越出 allowlist 同
        ToolControlPlaneException wideWindow = catchThrowableOfType(
                () -> executor.execute(new ToolExecutor.ToolExecution(
                        Map.of("since", Instant.now().minusSeconds(901).toString(),
                                "until", Instant.now().toString()),
                        System.currentTimeMillis() + 4_000, 65_536)),
                ToolControlPlaneException.class);
        assertThat(wideWindow.reason()).isEqualTo(ToolControlReason.INVALID_ARGS);

        ToolControlPlaneException ghostService = catchThrowableOfType(
                () -> executor.execute(new ToolExecutor.ToolExecution(
                        Map.of("since", since.toString(), "until", until.toString(),
                                "service", "ghost-svc"),
                        System.currentTimeMillis() + 4_000, 65_536)),
                ToolControlPlaneException.class);
        assertThat(ghostService.reason()).isEqualTo(ToolControlReason.INVALID_ARGS);

        // 201 行 → 200 行 + truncated 标记（卡面 limit 200）。
        // B-27：窗幅 must ≤900s——旧用例 until=now+1000s 自踩 1120s 窗被守卫正确拒绝
        for (int i = 0; i < 201; i++) {
            seedDeploymentRow("it-bulk-" + i, Instant.now().plusSeconds(i + 10), "SUCCEEDED");
        }
        byte[] bulk = executor.execute(new ToolExecutor.ToolExecution(
                Map.of("since", Instant.now().minusSeconds(120).toString(),
                        "until", Instant.now().plusSeconds(300).toString()),
                System.currentTimeMillis() + 4_000, 65_536));
        Map<?, ?> bulkData = (Map<?, ?>) new ObjectMapper().readValue(bulk, Map.class).get("data");
        assertThat((List<?>) bulkData.get("result")).hasSize(200);
        assertThat(bulkData.get("truncated")).isEqualTo(Boolean.TRUE);
    }

    // -------------------------------------------------- ④ 部署写路径与幂等锚

    @Test
    @DisplayName("deploy_app SET ROLE 写入；同 deploy_id 重放 ON CONFLICT 零新增；deploy_app 不可读")
    void deploymentWritePathIsRoleSplitAndIdempotent() {
        seedDeploymentRow("it-deploy-idem", Instant.now(), "SUCCEEDED");
        // 同 deploy_id 二次落档（脚本重试/重放）：锚拒，零重复生效事件且首态不改写
        seedDeploymentRow("it-deploy-idem", Instant.now(), "FAILED");
        assertThat(count("change_event")).isEqualTo(1L);
        assertThat(adminJdbc.sql("SELECT status FROM change_event "
                        + "WHERE deploy_id = 'it-deploy-idem'")
                .query(String.class).single()).isEqualTo("SUCCEEDED");

        // deploy_app 行为面：可写不可读（写路径与 control_app 读面分离的执行证据）。
        // SET LOCAL ROLE 事务级生效——commit/rollback 都自动还原，池化连接零身份泄漏
        assertThatThrownBy(() -> adminTx.executeWithoutResult(s -> {
            adminJdbc.sql("set local role deploy_app").update();
            adminJdbc.sql("SELECT count(*) FROM change_event").query(Long.class).single();
        })).isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------ 种子

    /** 部署事实行（deploy_app 写路径：SET LOCAL ROLE + INSERT 同连接事务——池化防异连）。
     * B-25：目标式 ON CONFLICT 需要冲突列 SELECT（insert-only 角色 42501 拒），
     * 用无目标式 on conflict do nothing——幂等锚仍落在 (source, deploy_id) 唯一约束。 */
    private static void seedDeploymentRow(String deployId, Instant effectiveAt, String status) {
        adminTx.executeWithoutResult(s -> {
            adminJdbc.sql("set local role deploy_app").update();
            adminJdbc.sql("""
                    insert into change_event (id, deploy_id, source, action, service,
                        environment, image_digest, config_digest, commit_sha, actor,
                        started_at, effective_at, rollback_of, status)
                    values (:id, :deployId, 'deployment', 'DEPLOY', 'control-app',
                        'production', null, null, null, 'it-deploy-script',
                        :effectiveAt, :effectiveAt, null, :status)
                    on conflict do nothing
                    """)
                    .param("id", UUID.randomUUID())
                    .param("deployId", deployId)
                    .param("effectiveAt", java.sql.Timestamp.from(effectiveAt))
                    .param("status", status)
                    .update();
        });
    }
}
