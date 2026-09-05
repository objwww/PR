package com.objwww.pr.notify.domain.service;

import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderException;
import com.objwww.pr.notify.domain.service.NotificationRenderer.RenderedNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M3-21 白名单渲染：白名单外字段不取、控制字符剥离、@all 中和、Markdown 链接 scheme
 * 白名单、超长截断、秘密值遮蔽；payload 缺 operation_id/不可解析确定性拒绝。
 */
class NotificationRendererTest {

    private final NotificationRenderer renderer = new NotificationRenderer(120, 600);

    private static final String PAYLOAD = """
            {"operation_id":"%s","report_id":"r-1","run_id":"run-1","attempt_id":"a-1",
             "candidate":true,"notice":"AI 候选结论，未完成证据语义验证",
             "summary":"支付错误率升高","impact":"下单成功率下降",
             "remediation":"检查支付依赖","root_cause_component":"payment",
             "root_cause_fault_type":"BUSINESS_ERROR_RATE",
             "root_cause_reason_code":"PAYMENT_CHARGE_FAILURE",
             "raw_report":"这段绝不能进通知"}
            """.formatted(UUID.randomUUID());

    @Test
    @DisplayName("白名单渲染：必备字段进文案、operation_id 在尾部可检测；raw_report 等白名单外字段被忽略")
    void rendersWhitelistOnly() {
        RenderedNotification rendered = renderer.render(PAYLOAD);

        assertThat(rendered.operationId()).isNotNull();
        assertThat(rendered.text())
                .contains("AI 候选结论")
                .contains("payment")
                .contains("PAYMENT_CHARGE_FAILURE")
                .contains("operation_id: " + rendered.operationId())
                .doesNotContain("raw_report")
                .doesNotContain("这段绝不能进通知")
                .doesNotContain("attempt_id");
        assertThat(rendered.title()).contains("【AI 候选】");
    }

    @Test
    @DisplayName("注入面消毒：控制字符剥离、@all 中和、非 http(s) 链接剥成纯文本、秘密值遮蔽")
    void sanitizesInjectionVectors() {
        String nasty = renderer.sanitize("line1\u0007line2\u001B[31m");
        assertThat(nasty).doesNotContain("\u0007").doesNotContain("\u001B");

        assertThat(renderer.sanitize("出问题了 @all 快看 @everyone"))
                .isEqualTo("出问题了 @ all 快看 @ all");

        assertThat(renderer.sanitize("[正常](https://a.b/c) [危险](javascript:x)"))
                .isEqualTo("[正常](https://a.b/c) 危险");

        assertThat(renderer.sanitize("key sk-abcdefgh12345678 与 Bearer x.y.z-99987655"))
                .contains("[REDACTED]").doesNotContain("sk-abcdefgh");
        assertThat(renderer.sanitize("password = \"supersecret99\""))
                .contains("[REDACTED]").doesNotContain("supersecret99");
    }

    @Test
    @DisplayName("超长截断：单字段上限与总文本上限生效，截断留省略号")
    void truncatesOverlongFields() {
        NotificationRenderer tight = new NotificationRenderer(20, 200);
        String longSummary = "很长的摘要".repeat(20);
        RenderedNotification rendered = tight.render(
                PAYLOAD.replace("支付错误率升高", longSummary));

        assertThat(rendered.text().length()).isLessThanOrEqualTo(200);
        assertThat(rendered.text()).contains("…").doesNotContain(longSummary);
    }

    @Test
    @DisplayName("确定性拒绝：payload 不可解析或缺 operation_id → RenderException（执行器落 DEAD）")
    void rejectsContractViolations() {
        assertThatThrownBy(() -> renderer.render("not-json"))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("不可解析");

        assertThatThrownBy(() -> renderer.render(
                "{\"summary\":\"缺 operation_id\"}"))
                .isInstanceOf(RenderException.class)
                .hasMessageContaining("operation_id");
    }

    @Test
    @DisplayName("候选未标记：candidate=false 时标题不带【AI 候选】（触发点语义不冒充）")
    void candidateMarkerFollowsPayload() {
        RenderedNotification rendered = renderer.render(
                PAYLOAD.replace("\"candidate\":true", "\"candidate\":false"));
        assertThat(rendered.title()).doesNotContain("【AI 候选】");
    }
}
