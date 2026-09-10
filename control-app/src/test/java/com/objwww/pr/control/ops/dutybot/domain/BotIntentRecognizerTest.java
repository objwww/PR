package com.objwww.pr.control.ops.dutybot.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BotIntentRecognizer 单测（UX-02）：各意图分支正/误用例、相对/绝对日期解析、
 * 状态与服务词元提取、优先级裁决（值班词 &gt; 通知词 &gt; 告警词；UUID 只在告警
 * 语境升级为详情）、输入有界（超长截断仍识别）。
 */
class BotIntentRecognizerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    @Test
    void helpWordsAreRecognized() {
        assertThat(BotIntentRecognizer.recognize("帮助", TODAY).kind())
                .isEqualTo(BotIntent.Kind.HELP);
        assertThat(BotIntentRecognizer.recognize("help me", TODAY).kind())
                .isEqualTo(BotIntent.Kind.HELP);
        assertThat(BotIntentRecognizer.recognize("你会什么", TODAY).kind())
                .isEqualTo(BotIntent.Kind.HELP);
    }

    @Test
    void dutyNowAndRelativeDates() {
        BotIntent tonight = BotIntentRecognizer.recognize("今晚谁值班", TODAY);
        assertThat(tonight.kind()).isEqualTo(BotIntent.Kind.DUTY_ONCALL);
        assertThat(tonight.date()).isEqualTo(TODAY);

        assertThat(BotIntentRecognizer.recognize("明天谁值班", TODAY).date())
                .isEqualTo(TODAY.plusDays(1));
        assertThat(BotIntentRecognizer.recognize("后天谁当班", TODAY).date())
                .isEqualTo(TODAY.plusDays(2));
        assertThat(BotIntentRecognizer.recognize("昨天谁在值班", TODAY).date())
                .isEqualTo(TODAY.minusDays(1));
        // 无日期词 → null（此刻）
        assertThat(BotIntentRecognizer.recognize("现在谁值班", TODAY).date()).isNull();
    }

    @Test
    void dutyExplicitDates() {
        assertThat(BotIntentRecognizer.recognize("2026-09-15 谁值班", TODAY).date())
                .isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(BotIntentRecognizer.recognize("9月20日谁当班", TODAY).date())
                .isEqualTo(LocalDate.of(2026, 9, 20));
        // 中文日期已过去 60 天以上 → 明年
        assertThat(BotIntentRecognizer.recognize("1月5日谁值班", TODAY).date())
                .isEqualTo(LocalDate.of(2027, 1, 5));
        // 非法日期如实放弃解析 = 此刻
        assertThat(BotIntentRecognizer.recognize("2月30日谁值班", TODAY).date()).isNull();
    }

    @Test
    void notifyWordsAreRecognized() {
        assertThat(BotIntentRecognizer.recognize("通知发出去了吗", TODAY).kind())
                .isEqualTo(BotIntent.Kind.NOTIFY_STATUS);
        // 通知词优先于告警词（"告警通知发出去没"是投递面问题）
        assertThat(BotIntentRecognizer.recognize("告警通知发出去没有", TODAY).kind())
                .isEqualTo(BotIntent.Kind.NOTIFY_STATUS);
        assertThat(BotIntentRecognizer.recognize("outbox 状态怎么样", TODAY).kind())
                .isEqualTo(BotIntent.Kind.NOTIFY_STATUS);
    }

    @Test
    void incidentQueryDefaultsToFiring() {
        BotIntent intent = BotIntentRecognizer.recognize("现在有哪些告警", TODAY);
        assertThat(intent.kind()).isEqualTo(BotIntent.Kind.INCIDENT_QUERY);
        assertThat(intent.status()).isEqualTo("FIRING");
        assertThat(intent.service()).isNull();
    }

    @Test
    void incidentStatusAndServiceTokens() {
        assertThat(BotIntentRecognizer.recognize("已恢复的告警有哪些", TODAY).status())
                .isEqualTo("RESOLVED");
        assertThat(BotIntentRecognizer.recognize("全部告警", TODAY).status()).isNull();

        BotIntent byService = BotIntentRecognizer.recognize("服务 order-arena 的告警", TODAY);
        assertThat(byService.service()).isEqualTo("order-arena");
        assertThat(BotIntentRecognizer.recognize("查一下 服务:notify-app 告警", TODAY).service())
                .isEqualTo("notify-app");
    }

    @Test
    void incidentDetailRequiresIncidentWordAndUuid() {
        UUID id = UUID.randomUUID();
        BotIntent detail = BotIntentRecognizer.recognize("这个告警什么情况 " + id, TODAY);
        assertThat(detail.kind()).isEqualTo(BotIntent.Kind.INCIDENT_DETAIL);
        assertThat(detail.incidentId()).isEqualTo(id);

        // 裸 UUID 零语境不猜 → UNSUPPORTED
        assertThat(BotIntentRecognizer.recognize(id.toString(), TODAY).kind())
                .isEqualTo(BotIntent.Kind.UNSUPPORTED);
    }

    @Test
    void dutyWordWinsOverIncidentWord() {
        assertThat(BotIntentRecognizer.recognize("值班的人知道告警情况吗", TODAY).kind())
                .isEqualTo(BotIntent.Kind.DUTY_ONCALL);
    }

    @Test
    void unknownAndBlankAreUnsupported() {
        assertThat(BotIntentRecognizer.recognize("你好", TODAY).kind())
                .isEqualTo(BotIntent.Kind.UNSUPPORTED);
        assertThat(BotIntentRecognizer.recognize("   ", TODAY).kind())
                .isEqualTo(BotIntent.Kind.UNSUPPORTED);
        assertThat(BotIntentRecognizer.recognize(null, TODAY).kind())
                .isEqualTo(BotIntent.Kind.UNSUPPORTED);
    }

    @Test
    void overlongInputIsBoundedAndStillRecognized() {
        String longText = "今晚谁值班" + "冗".repeat(1000);
        assertThat(BotIntentRecognizer.recognize(longText, TODAY).kind())
                .isEqualTo(BotIntent.Kind.DUTY_ONCALL);
    }
}
