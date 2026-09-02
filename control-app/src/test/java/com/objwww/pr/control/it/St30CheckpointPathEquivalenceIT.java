package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.objwww.pr.control.application.StepOutcome;
import com.objwww.pr.control.application.WorkItemWorker;
import com.objwww.pr.control.domain.ai.MockModelGateway;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-30（方案 §11 L3 表，回指 §4.2/I19）——checkpoint 命中后正常闭环，
 * 与无 checkpoint（首次冷路径）全表对比等价。
 *
 * <p>场景：同一 subject 上两个等价输入的 Run（同 aggregate_key）：
 * <ul>
 *   <li>Run X：attempt#1 模型执行 + checkpoint 提交后崩溃（T2 前窗口）→ attempt#2
 *       零模型续跑 → T2 闭环；</li>
 *   <li>Run Y：无历史 checkpoint，单 attempt 冷路径直达闭环。</li>
 * </ul>
 * 两侧模型产出逐字节相同（同输入同 mock 应答）。
 *
 * <p>预期断言（全表对比，UUID/时间戳/sequence 等固有随机量除外）：
 * review_finding 逐字段一致；outbox_command 的 command_type/state/fence_mode/policy
 * 一致、payload 剔除易变键（operation_id/marker/run_id/revision_id）后逐字段一致、
 * 依赖边同构（PUBLISH_REVIEW → REQUIRE_CONFIRMED → CREATE_CHECK）；Step 产出与
 * review_finding 逐字段一致（fingerprint 含 head_sha 属固有差异，剔除后比较——TB-09）；
 * 事件类型序列剔除 CHECKPOINT_* 后一致。
 *
 * <p>范围说明：闭环推进到 control 侧终态（Step SUCCEEDED / Run REVIEW_COMPLETE /
 * outbox PENDING）；方案表述的 CONFIRMED 属 publisher T3 确认面，不在本 IT
 * 活动范围（control it/）内，等价性以铸命令内容为准。
 *
 * <p>取证：review_finding / outbox_command（payload 经 CAS 回读）/ run_step /
 * execution_event。
 */
class St30CheckpointPathEquivalenceIT extends PostgresITBase {

    /** payload 中固有随机/引用键（含两 Run 不同的输入标识 head_sha/commit_id），对比前剔除 */
    private static final Set<String> VOLATILE_PAYLOAD_KEYS =
            Set.of("operation_id", "marker", "run_id", "revision_id", "head_sha", "commit_id");

    @TempDir
    Path casDir;

    private StCheckpointHarness h;

    @BeforeEach
    void setUp() {
        h = new StCheckpointHarness(casDir);
    }

    @Test
    void resumedPathIsEquivalentToColdPath() throws Exception {
        // ---- Run X：checkpoint 提交后崩溃 → 零模型续跑闭环
        StCheckpointHarness.Seed seedX = h.seedFirstRun(110, "head-st30-x", StCheckpointHarness.PROMPT_V1);
        MockModelGateway modelX = StCheckpointHarness.modelReturningOutput();
        StCheckpointHarness.Claimed first = h.claim("st30-worker-a");
        StepOutcome crashed = h.newReviewExecutor(modelX).execute(first.context(), () -> true);
        assertThat(crashed).isInstanceOf(StepOutcome.Succeeded.class);
        // —— 崩溃：不 T2 ——
        h.forceLeaseExpired(seedX.workItemId());
        var workerX = h.newWorker("st30-worker-b", modelX);
        assertThat(workerX.recoverExpiredLeases()).isEqualTo(1);
        StCheckpointHarness.Claimed resumed = h.claim("st30-worker-b");
        StepOutcome hit = h.newReviewExecutor(modelX).execute(resumed.context(), () -> true);
        assertThat(modelX.requests()).as("Run X 全程模型恰 1 次（续跑零调用）").hasSize(1);
        h.complete(resumed, hit);

        // ---- Run Y：同 subject 同输入，冷路径直达闭环
        StCheckpointHarness.Seed seedY = h.seedRunOnSubject(seedX.subjectId(), 110, "head-st30-y",
                StCheckpointHarness.PROMPT_V1);
        MockModelGateway modelY = StCheckpointHarness.modelReturningOutput();
        WorkItemWorker workerY = h.newWorker("st30-worker-c", modelY);
        workerY.runOnce();
        assertThat(modelY.requests()).hasSize(1);

        // ---- 终态等价
        assertThat(h.stepState(seedX.stepId())).isEqualTo(h.stepState(seedY.stepId()))
                .isEqualTo("SUCCEEDED");
        assertThat(h.runState(seedX.runId())).isEqualTo(h.runState(seedY.runId()))
                .isEqualTo("REVIEW_COMPLETE");
        String outputX = adminJdbc.sql("SELECT output_artifact_digest FROM run_step WHERE id = :id")
                .param("id", seedX.stepId()).query(String.class).single();
        String outputY = adminJdbc.sql("SELECT output_artifact_digest FROM run_step WHERE id = :id")
                .param("id", seedY.stepId()).query(String.class).single();
        // TB-09：fingerprint 组分含 head_sha（FindingMapper 契约），两 Run 头不同 →
        // 原始 digest 必然不同；等价性以剔除 fingerprint 后的 JSON 树逐字段比较
        // （与下方 outbox payload 剔除 head_sha 等易变键同一原则）
        assertThat(normalizedStepOutput(outputX))
                .as("两路径 Step 产出逐字段一致（剔除 head_sha 衍生的 fingerprint）")
                .isEqualTo(normalizedStepOutput(outputY));

        // ---- finding 逐字段一致（剔除 id/run/revision/时间戳）
        assertThat(findingsOf(seedX.runId())).isEqualTo(findingsOf(seedY.runId()));

        // ---- outbox 命令逐字段一致（剔除 operation_id/sequence/payload digest 等随机量）
        assertThat(outboxShape(seedX.runId())).isEqualTo(outboxShape(seedY.runId()));
        assertThat(normalizedPayloads(seedX.runId()))
                .as("outbox payload 剔除易变键后逐字段一致")
                .isEqualTo(normalizedPayloads(seedY.runId()));
        assertThat(dependencyShape(seedX.runId())).isEqualTo(dependencyShape(seedY.runId()));
        // sequence 各自连续（同 subject 共享取号器：X 领 1,2，Y 领 3,4）
        assertThat(sequencesOf(seedX.runId())).containsExactly(1L, 2L);
        assertThat(sequencesOf(seedY.runId())).containsExactly(3L, 4L);

        // ---- 事件序列剔除 checkpoint 族后一致
        assertThat(eventTypesExcludingCheckpoint(seedX.runId()))
                .isEqualTo(eventTypesExcludingCheckpoint(seedY.runId()));
    }

    /** Step 产出 JSON 从 CAS 回读，剔除 head_sha 衍生的 fingerprint 后返回 JSON 树 */
    private JsonNode normalizedStepOutput(String digest) throws Exception {
        byte[] body = h.cas.get(new Digest(digest)).orElseThrow();
        JsonNode tree = h.om.readTree(new String(body, StandardCharsets.UTF_8));
        stripFingerprints(tree);
        return tree;
    }

    private List<String> findingsOf(UUID runId) {
        // fingerprint 含 head_sha（TB-09：两 Run 头不同必然不等），逐字段等价不含它
        return adminJdbc.sql("""
                SELECT rule_id || '|' || severity || '|' || file_path
                       || '|' || line_start || '|' || line_end || '|' || state
                  FROM review_finding WHERE review_run_id = :r ORDER BY file_path, line_start, rule_id
                """).param("r", runId).query(String.class).list();
    }

    private List<String> outboxShape(UUID runId) {
        return adminJdbc.sql("""
                SELECT command_type || '|' || state || '|' || fence_mode || '|' || policy_version
                       || '|' || aggregate_key
                  FROM outbox_command WHERE review_run_id = :r ORDER BY aggregate_sequence
                """).param("r", runId).query(String.class).list();
    }

    /** payload 从 CAS 回读，剔除固有随机/引用键后按 command_type 索引（JSON 树等值比较）；
     *  findings[].fingerprint 等嵌套的 head_sha 衍生字段递归剔除（TB-09 复验注） */
    private java.util.Map<String, JsonNode> normalizedPayloads(UUID runId) throws Exception {
        record Row(String type, String digest) {
        }
        List<Row> rows = adminJdbc.sql("""
                SELECT command_type, payload_artifact_digest FROM outbox_command
                 WHERE review_run_id = :r ORDER BY aggregate_sequence
                """).param("r", runId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2))).list();
        java.util.Map<String, JsonNode> result = new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            byte[] body = h.cas.get(new Digest(row.digest())).orElseThrow();
            JsonNode tree = h.om.readTree(new String(body, StandardCharsets.UTF_8));
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            VOLATILE_PAYLOAD_KEYS.forEach(obj::remove);
            stripFingerprints(tree);
            result.put(row.type(), tree);
        }
        return result;
    }

    /** 递归剔除所有名为 fingerprint 的字段（组分含 head_sha，两 Run 头不同必然不等） */
    private static void stripFingerprints(JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            obj.remove("fingerprint");
            obj.forEach(St30CheckpointPathEquivalenceIT::stripFingerprints);
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode arr) {
            arr.forEach(St30CheckpointPathEquivalenceIT::stripFingerprints);
        }
    }

    private List<String> dependencyShape(UUID runId) {
        return adminJdbc.sql("""
                SELECT c.command_type || '->' || d.command_type || ':' || od.dependency_mode
                  FROM outbox_dependency od
                  JOIN outbox_command c ON c.operation_id = od.operation_id
                  JOIN outbox_command d ON d.operation_id = od.depends_on_operation_id
                 WHERE c.review_run_id = :r ORDER BY 1
                """).param("r", runId).query(String.class).list();
    }

    private List<Long> sequencesOf(UUID runId) {
        return adminJdbc.sql("""
                SELECT aggregate_sequence FROM outbox_command
                 WHERE review_run_id = :r ORDER BY aggregate_sequence
                """).param("r", runId).query(Long.class).list();
    }

    private List<String> eventTypesExcludingCheckpoint(UUID runId) {
        return adminJdbc.sql("""
                SELECT event_type FROM execution_event
                 WHERE review_run_id = :r AND event_type NOT LIKE 'CHECKPOINT%'
                 ORDER BY position
                """).param("r", runId).query(String.class).list();
    }
}
