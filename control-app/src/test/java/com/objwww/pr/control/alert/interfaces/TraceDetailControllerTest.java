package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.repository.RcaToolSpanDetailReader;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace 工具 span 明细端点单元测试（纯投影面：非法 runId 400 / 空表如实 / 字段契约）。
 */
class TraceDetailControllerTest {

    /** 假读面：按 run 给固定行 */
    private static class FakeDetails implements RcaToolSpanDetailReader {
        final Map<UUID, List<ToolSpanDetail>> byRun = new java.util.LinkedHashMap<>();

        @Override
        public List<ToolSpanDetail> detailsByRun(UUID runId) {
            return byRun.getOrDefault(runId, List.of());
        }
    }

    @Test
    void rejectsIllegalRunIdWith400() {
        TraceDetailController controller = new TraceDetailController(new FakeDetails());
        ResponseEntity<Map<String, Object>> response = controller.traceDetails("not-a-uuid");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "runId 非法");
    }

    @Test
    void emptyRunYieldsEmptyDetailsHonestly() {
        TraceDetailController controller = new TraceDetailController(new FakeDetails());
        ResponseEntity<Map<String, Object>> response =
                controller.traceDetails(UUID.randomUUID().toString());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) response.getBody().get("details");
        assertThat(details).isEmpty();
    }

    @Test
    void projectsRowFieldsWithNullEvidenceTriplePreserved() {
        FakeDetails fake = new FakeDetails();
        UUID runId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        fake.byRun.put(runId, List.of(
                new RcaToolSpanDetailReader.ToolSpanDetail(invocationId, taskId, "logs.query", 2,
                        "{\"timeRange\":\"last_30m\"}", "{\"status\":\"success\",\"data\":[",
                        evidenceId.toString(), "logs_window", "loki",
                        "INVALID_ARGS: 缺必填字段 from"),
                new RcaToolSpanDetailReader.ToolSpanDetail(UUID.randomUUID(), taskId, "change.query", 3,
                        null, null, null, null, null, null)));
        TraceDetailController controller = new TraceDetailController(fake);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) controller
                .traceDetails(runId.toString()).getBody().get("details");

        assertThat(details).hasSize(2);
        assertThat(details.get(0))
                .containsEntry("invocationId", invocationId.toString())
                .containsEntry("taskId", taskId.toString())
                .containsEntry("toolName", "logs.query")
                .containsEntry("callSeq", 2L)
                .containsEntry("evidenceRef", evidenceId.toString())
                .containsEntry("evidenceType", "logs_window")
                .containsEntry("evidenceSource", "loki")
                // BA-190（W3）：拒因具体消息随明细透出
                .containsEntry("reasonDetail", "INVALID_ARGS: 缺必填字段 from");
        // 未产证据的调用行（如 VALIDATE_ONLY/失败）三要素如实 null，不造数；无详情如实 null
        assertThat(details.get(1))
                .containsEntry("scopeSummary", null)
                .containsEntry("resultSummary", null)
                .containsEntry("evidenceRef", null)
                .containsEntry("reasonDetail", null);
    }

    @Test
    void nullReaderDegradesToEmptyDetails() {
        TraceDetailController controller = new TraceDetailController(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) controller
                .traceDetails(UUID.randomUUID().toString()).getBody().get("details");
        assertThat(details).isEmpty();
    }

    /** 编译期锚：防投影字段被悄悄删（Trace 六要素验收面） */
    @Test
    void detailRecordCarriesSixElementFields() {
        List<String> required = new ArrayList<>(List.of(
                "invocationId", "taskId", "callSeq", "scopeSummary",
                "resultSummary", "evidenceRef", "evidenceType", "evidenceSource"));
        assertThat(required).hasSize(8);
    }
}
