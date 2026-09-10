package com.objwww.pr.notify.domain.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-14 值班渲染锚点：duty payload 形状（type/operation_id/source/severity/title/body）
 * → 值班消息（非 RCA 报告卡片）；消毒面复用父类（秘密遮蔽/@all 中和）。
 */
class DutyNotificationRendererTest {

    private final DutyNotificationRenderer renderer = new DutyNotificationRenderer(500, 3800);

    @Test
    void rendersDutyShapeNotReportCard() {
        String payload = """
                {"type":"duty","operation_id":"%s","source":"RCA_SYSTEM",
                 "severity":"P2","title":"值班告警 RCA_SYSTEM_LLM [firing]",
                 "body":"receiver=rca-oncall groupKey=g:1"}
                """.formatted(UUID.randomUUID());

        NotificationRenderer.RenderedNotification out = renderer.render(payload);

        assertThat(out.title()).startsWith("【值班】");
        assertThat(out.title()).contains("RCA_SYSTEM_LLM");
        assertThat(out.text()).contains("- **source**: RCA_SYSTEM");
        assertThat(out.text()).contains("- **severity**: P2");
        assertThat(out.text()).contains("receiver=rca-oncall");
        assertThat(out.text()).contains("operation_id: ");
        // 不是报告卡片：根因三元组/报告 id 字段不出现在值班消息
        assertThat(out.text()).doesNotContain("root_cause").doesNotContain("report_id");
    }

    @Test
    void missingOperationIdIsDeterministicReject() {
        assertThatThrownBy(() -> renderer.render(
                "{\"type\":\"duty\",\"source\":\"MANUAL\",\"severity\":\"P3\",\"title\":\"t\",\"body\":\"b\"}"))
                .isInstanceOf(NotificationRenderer.RenderException.class);
    }

    @Test
    void sanitizationIsInherited() {
        String payload = """
                {"type":"duty","operation_id":"%s","source":"MANUAL","severity":"P3",
                 "title":"t","body":"token=abcdefgh12345678 请 @all 查看"}
                """.formatted(UUID.randomUUID());

        String text = renderer.render(payload).text();

        assertThat(text).contains("[REDACTED]");
        assertThat(text).doesNotContain("abcdefgh12345678");
        assertThat(text).contains("@ all");
    }
}
