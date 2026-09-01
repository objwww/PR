package com.objwww.pr.publisher.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-29（M2 方案 §11/L4，回指 I27/AFT-16）：repair 命令 payload 异常一律 fail-closed。
 *
 * <p>注入（三方法共用铸单链：CHECK_RUN 漂移 MISSING → AUTO repair DISPATCHED，命令 PENDING）：
 * ① 原 payload 从 CAS 目录删除（缺失）；② CAS 文件覆写为畸形 JSON（损坏/篡改——reader 按
 * digest 路径取件，内容不再可信即不可读）；③ 合法 JSON 注入 raw 寻址/凭证字段
 * （{@code url}/{@code method}/{@code token}，operation_id/installation_id 等合法字段保持原值，
 * 唯一违例即注入键）。
 *
 * <p>断言：命令 FAILED_TERMINAL + SAFETY_REJECTED 告警落账（①② 错误码 PAYLOAD_UNAVAILABLE，
 * ③ SCHEMA_REJECTED 且违例清单含"含禁止的寻址/凭证字段"）；领取全程零触网（读探针都未发出，
 * 零远端写）；RepairOutcomeProjector 收敛 request → FAILED_TERMINAL + REPAIR_FAILED 事件；
 * 资源行不误改（CHECK_RUN 保持 MISSING，不冒充已修）。
 *
 * <p>取证：outbox_command(state/last_error_code)、execution_event(SAFETY_REJECTED/REPAIR_FAILED)、
 * repair_request(state/last_error)、WireMock serve event 计数、CAS 目录文件。
 *
 * <p>复原：每方法 TRUNCATE 全表（基座）+ 独立 WireMock 实例 + 独立 CAS 目录（@TempDir）。
 */
class Ex29RepairPayloadFailClosedIT extends PostgresITBase {

    private static final String HEAD = "head" + "2".repeat(36);
    private static final String REPO = "objwww/mall";
    private static final int PR = 29;
    private static final long REPO_ID = 2029L;

    private WireMockServer wiremock;
    private ItHarness harness;
    private UUID repairOp;
    private Path casFile;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());

        ExRepairChain.publishPair(harness, wiremock, "ex29-d1", REPO_ID, REPO, PR, HEAD);
        repairOp = ExRepairChain.driftCheckToMissingAndPlan(harness, wiremock, REPO, HEAD);

        // repair 命令 payload 的 CAS 取件路径（CasPayloadReader 同式：casDir/<hash前2位>/<hash>）
        String payloadHash = adminJdbc.sql(
                "SELECT payload_hash FROM outbox_command WHERE operation_id = :id")
                .param("id", repairOp).query(String.class).single();
        casFile = casDir.resolve(payloadHash.substring(0, 2)).resolve(payloadHash);
        if (!Files.isRegularFile(casFile)) {
            throw new IllegalStateException("夹具：repair payload CAS 文件不存在: " + casFile);
        }
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void missingOriginalPayloadFailsClosed() throws Exception {
        Files.delete(casFile);
        assertFailsClosedWithoutRemoteContact("PAYLOAD_UNAVAILABLE");
    }

    @Test
    void corruptCasPayloadFailsClosed() throws Exception {
        Files.writeString(casFile, "{corrupt-json");
        assertFailsClosedWithoutRemoteContact("PAYLOAD_UNAVAILABLE");
    }

    @Test
    void forbiddenAddressAndTokenKeyInjectionRejected() throws Exception {
        Map<String, Object> payload = OM.readValue(Files.readString(casFile),
                new TypeReference<>() {
                });
        payload.put("url", "https://evil.example.com/hook");
        payload.put("method", "DELETE");
        payload.put("token", "ghp_forbidden");
        Files.writeString(casFile, OM.writeValueAsString(payload));

        assertFailsClosedWithoutRemoteContact("SCHEMA_REJECTED");
        // Validator 拒绝清单留痕（AFT-16 取证面）
        assertThat(adminJdbc.sql("""
                SELECT count(*) FROM execution_event
                 WHERE event_type = 'SAFETY_REJECTED'
                   AND payload::text LIKE '%含禁止的寻址/凭证字段%'
                """).query(Long.class).single()).isEqualTo(1);
    }

    /** 三注入共用的断言链：fail-closed 终态 + 零触网 + Projector 收敛 + 资源不误改 */
    private void assertFailsClosedWithoutRemoteContact(String expectedErrorCode) {
        int serveEventsBefore = wiremock.getAllServeEvents().size();
        harness.newClaimer().runOnce();

        // 零触网（fence/校验在 T3-A 事务内拒绝，连 probe-first 读都未发生）
        assertThat(wiremock.getAllServeEvents()).hasSize(serveEventsBefore);
        assertThat(adminJdbc.sql(
                "SELECT state || ':' || last_error_code FROM outbox_command WHERE operation_id = :id")
                .param("id", repairOp).query(String.class).single())
                .isEqualTo("FAILED_TERMINAL:" + expectedErrorCode);
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'SAFETY_REJECTED'")
                .query(Long.class).single()).isEqualTo(1);

        // Projector 收敛卡 DISPATCHED 的 request → FAILED_TERMINAL + REPAIR_FAILED
        assertThat(harness.newRepairOutcomeProjector().runOnce()).isEqualTo(1);
        assertThat(adminJdbc.sql("SELECT state || ':' || last_error FROM repair_request")
                .query(String.class).single()).isEqualTo("FAILED_TERMINAL:COMMAND_FAILED_TERMINAL");
        assertThat(adminJdbc.sql(
                "SELECT count(*) FROM execution_event WHERE event_type = 'REPAIR_FAILED'")
                .query(Long.class).single()).isEqualTo(1);

        // 资源行不误改：check 保持 MISSING（不冒充已修），review 不受影响
        assertThat(adminJdbc.sql("SELECT state FROM publication_resource ORDER BY created_at")
                .query(String.class).list()).containsExactly("MISSING", "PRESENT");
    }
}
