package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.PublicationState;
import com.objwww.pr.control.alert.domain.model.ReportPublication;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader.RcaReportView;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportQueryService 单测（内存假件）：三态投影（NONE/OK/REJECTED）、最新行取舍
 * （created_at 乱序输入防御排序）、supersededCount、packageJson 可解析→JSON 树 /
 * 畸形→原样字符串、publication 无行 null 如实。
 */
class ReportQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final UUID RUN_ID = UUID.randomUUID();

    private final List<RcaReportView> reportRows = new ArrayList<>();
    private final Map<UUID, ReportPublication> publicationRows = new LinkedHashMap<>();
    private final ReportQueryService service = new ReportQueryService(
            runId -> reportRows, new FakePublications(publicationRows), new ObjectMapper());

    @Test
    void emptyRowsProjectNone() {
        Map<String, Object> out = service.report(RUN_ID);

        assertThat(out).containsExactly(Map.entry("state", "NONE"));
    }

    @Test
    void latestRowWinsRegardlessOfInputOrder() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        // 乱序喂入：服务层自排序，不依赖实现方 ORDER BY
        reportRows.add(report(newer, ValidationStatus.STRUCTURE_VALIDATED, NOW, "{\"summary\":\"新\"}"));
        reportRows.add(report(older, ValidationStatus.STRUCTURE_VALIDATED,
                NOW.minusSeconds(120), "{\"summary\":\"旧\"}"));

        Map<String, Object> out = service.report(RUN_ID);

        assertThat(out.get("reportId")).isEqualTo(newer.toString());
        assertThat(out.get("supersededCount")).isEqualTo(1);
    }

    @Test
    void validationStatusMapsToOkOrRejected() {
        reportRows.add(report(UUID.randomUUID(), ValidationStatus.STRUCTURE_VALIDATED, NOW, "{}"));
        assertThat(service.report(RUN_ID).get("state")).isEqualTo("OK");

        reportRows.clear();
        reportRows.add(new RcaReportView(UUID.randomUUID(), RUN_ID, UUID.randomUUID(), 2,
                ValidationStatus.REJECTED_OVERSIZE, List.of("响应超尺寸上限"), "{}",
                "qwen-plus", 12, 22, 34, false, NOW));
        Map<String, Object> rejected = service.report(RUN_ID);
        assertThat(rejected.get("state")).isEqualTo("REJECTED");
        assertThat(rejected.get("validationStatus")).isEqualTo("REJECTED_OVERSIZE");
        assertThat(rejected.get("validationErrors")).isEqualTo(List.of("响应超尺寸上限"));
    }

    @Test
    void parseablePackageBecomesJsonTreeMalformedStaysRawString() {
        reportRows.add(report(UUID.randomUUID(), ValidationStatus.STRUCTURE_VALIDATED, NOW,
                "{\"schema_version\":2,\"summary\":\"s\"}"));
        Object parsed = service.report(RUN_ID).get("packageJson");
        assertThat(parsed).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) parsed).path("summary").asText()).isEqualTo("s");

        reportRows.clear();
        reportRows.add(report(UUID.randomUUID(), ValidationStatus.REJECTED_MALFORMED, NOW, "{broken"));
        assertThat(service.report(RUN_ID).get("packageJson")).isEqualTo("{broken");
    }

    @Test
    void publicationAbsentIsNullPresentIsProjected() {
        UUID reportId = UUID.randomUUID();
        reportRows.add(report(reportId, ValidationStatus.STRUCTURE_VALIDATED, NOW, "{}"));
        assertThat(service.report(RUN_ID).get("publication"))
                .as("无发布记录 → null 如实（不冒充 PENDING）").isNull();

        publicationRows.put(reportId, new ReportPublication(
                UUID.randomUUID(), reportId, PublicationState.RETRY_WAIT,
                null, null, 0, 2, 5, NOW, "{\"error\":\"smtp timeout\"}", NOW, NOW));
        @SuppressWarnings("unchecked")
        Map<String, Object> pub = (Map<String, Object>) service.report(RUN_ID).get("publication");
        assertThat(pub).containsEntry("state", "RETRY_WAIT")
                .containsEntry("attemptCount", 2)
                .containsEntry("maxAttempts", 5)
                .containsEntry("lastError", "{\"error\":\"smtp timeout\"}");
        assertThat(pub.get("updatedAt")).isEqualTo(NOW);
    }

    @Test
    void usageMissingAndNullTokensPassThroughHonestly() {
        reportRows.add(new RcaReportView(UUID.randomUUID(), RUN_ID, UUID.randomUUID(), 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), "{}", "qwen-plus",
                null, null, null, true, NOW));

        Map<String, Object> out = service.report(RUN_ID);

        assertThat(out.get("usageMissing")).isEqualTo(true);
        assertThat(out.get("promptTokens")).as("用量未回报 → null 不猜零").isNull();
        assertThat(out.get("totalTokens")).isNull();
    }

    private static RcaReportView report(UUID id, ValidationStatus status, Instant createdAt,
                                        String packageJson) {
        return new RcaReportView(id, RUN_ID, UUID.randomUUID(), 2, status, List.of(), packageJson,
                "qwen-plus", 12, 22, 34, false, createdAt);
    }

    private static final class FakePublications implements ReportPublicationRepository {
        private final Map<UUID, ReportPublication> rows;

        FakePublications(Map<UUID, ReportPublication> rows) {
            this.rows = rows;
        }

        @Override
        public void insert(ReportPublication publication) {
            rows.put(publication.reportId(), publication);
        }

        @Override
        public Optional<ReportPublication> findByReportId(UUID reportId) {
            return Optional.ofNullable(rows.get(reportId));
        }
    }
}
