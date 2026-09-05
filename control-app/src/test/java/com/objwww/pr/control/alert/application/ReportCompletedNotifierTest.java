package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry;
import com.objwww.pr.control.alert.domain.model.PublicationState;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-08/19 outbox 生产者：publication(READY) + 每渠道 outbox 同事务出生、
 * operation_id 贯穿（INV-AM3-2 重复可检测）、候选标记、payload 白名单与摘录上限。
 */
class ReportCompletedNotifierTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    private AlertInMemoryStores stores;
    private ReportCompletedNotifier notifier;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        notifier = new ReportCompletedNotifier(stores.publications, stores.outboxes,
                List.of("wecom", "test"), "am3-candidate-v1", 20);
    }

    private UUID notifyOnce() {
        UUID reportId = UUID.randomUUID();
        notifier.onReportValidated(reportId, UUID.randomUUID(), UUID.randomUUID(),
                "checkout 5xx 超阈值", "payment", "business_error_rate", "paymentFailure=50%",
                "支付成功率下降", "关闭故障注入开关", NOW);
        return reportId;
    }

    @Test
    @DisplayName("出生形态：publication READY + 每渠道一条 PENDING outbox，时间戳=就绪时刻")
    void createsPublicationAndPerChannelOutbox() {
        UUID reportId = notifyOnce();

        assertThat(stores.publications.all()).hasSize(1);
        var publication = stores.publications.findByReportId(reportId).orElseThrow();
        assertThat(publication.state()).isEqualTo(PublicationState.READY);
        assertThat(publication.createdAt()).isEqualTo(NOW);
        assertThat(publication.availableAt()).isEqualTo(NOW);
        assertThat(publication.maxAttempts()).isEqualTo(5);

        List<NotifyOutboxEntry> entries = stores.outboxes.findByPublicationId(publication.id());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(NotifyOutboxEntry::channel)
                .containsExactlyInAnyOrder("wecom", "test");
        assertThat(entries).allMatch(e -> e.state().name().equals("PENDING"));
        assertThat(entries).allMatch(e -> e.templateVersion().equals("am3-candidate-v1"));
    }

    @Test
    @DisplayName("INV-AM3-2：同一 operation_id 贯穿全部渠道行（重复投递可检测）")
    void singleOperationIdAcrossChannels() {
        UUID reportId = notifyOnce();
        var publication = stores.publications.findByReportId(reportId).orElseThrow();

        List<NotifyOutboxEntry> entries = stores.outboxes.findByPublicationId(publication.id());
        String operationId = entries.get(0).operationId().toString();
        assertThat(entries).allMatch(e -> e.operationId().toString().equals(operationId));
        assertThat(entries).allMatch(e -> e.payloadJson().contains(operationId));
    }

    @Test
    @DisplayName("候选标记：payload 必须带 candidate=true 与冻结文案")
    void payloadCarriesCandidateMarker() {
        UUID reportId = notifyOnce();

        String payload = stores.outboxes.findByPublicationId(
                        stores.publications.findByReportId(reportId).orElseThrow().id()).get(0)
                .payloadJson();
        assertThat(payload).contains("\"candidate\":true");
        assertThat(payload).contains(ReportCompletedNotifier.CANDIDATE_MARKER);
    }

    @Test
    @DisplayName("payload 白名单：只有渲染字段与审计标识，无报告正文/raw/工具原文")
    void payloadIsWhitelistOnly() {
        UUID reportId = notifyOnce();

        String payload = stores.outboxes.findByPublicationId(
                        stores.publications.findByReportId(reportId).orElseThrow().id()).get(0)
                .payloadJson();
        assertThat(payload).contains("summary").contains("root_cause_component")
                .contains("root_cause_fault_type").contains("root_cause_reason_code")
                .contains("impact").contains("remediation")
                .contains("report_id").contains("run_id").contains("attempt_id");
        // 白名单外的键一律不存在
        assertThat(payload).doesNotContain("package_json").doesNotContain("raw")
                .doesNotContain("evidence").doesNotContain("claims").doesNotContain("tool_call");
    }

    @Test
    @DisplayName("摘录上限：超长字段截断加省略号，不把整段报告塞进通知")
    void longFieldsTruncatedToExcerptCap() throws Exception {
        String longSummary = "长".repeat(100);
        notifier.onReportValidated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                longSummary, "payment", "business_error_rate", "rc", "impact", "fix", NOW);

        String payload = stores.outboxes.all().get(0).payloadJson();
        String summary = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(payload).path("summary").asText();
        assertThat(summary).hasSize(21).endsWith("…");
    }

    @Test
    @DisplayName("构造防御：channels 空 / templateVersion 空 / excerpt 上限 <1 拒绝")
    void constructorValidation() {
        assertThatThrownBy(() -> new ReportCompletedNotifier(
                stores.publications, stores.outboxes, List.of(), "t", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportCompletedNotifier(
                stores.publications, stores.outboxes, List.of("test"), " ", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportCompletedNotifier(
                stores.publications, stores.outboxes, List.of("test"), "t", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
