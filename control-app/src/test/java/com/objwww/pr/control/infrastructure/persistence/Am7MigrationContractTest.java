package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-11 本地静态门（AM7 §11 组件规则；范式沿 M7MigrationContractTest）：
 * Docker 不可用时也锁住 V43 八表的关键结构与权限边界。真 PostgreSQL 约束/授权
 * 行为由 195 真 PG IT 覆盖（flyway 43|t + 角色越权负向用例）。
 *
 * <p>评审六契约的 schema 面锚点：①episode/fingerprint 去重形状、③delivery 槽位
 * 唯一键、④status 只 UNREAD/READ（无 ACK 态）、⑤fallback 通道单行；密钥不落库
 * （duty_channel 无 webhook URL 列，INV-AM3-3）。
 */
class Am7MigrationContractTest {

    private static final Path V43 = Path.of(
            "src/main/resources/db/migration/V43__am7_duty.sql");

    private static String normalized() throws IOException {
        return Files.readString(V43).toLowerCase().replaceAll("\\s+", " ");
    }

    /** 表块切片：从 create table X 到下一 create（防注释里的词误中表内断言） */
    private static String block(String sql, String table) {
        int from = sql.indexOf("create table " + table);
        assertThat(from).as("表 " + table + " 必须存在").isGreaterThan(-1);
        int to = sql.indexOf("create table", from + 1);
        int idx = sql.indexOf("create index", from + 1);
        if (idx > 0 && (to < 0 || idx < to)) {
            to = idx;
        }
        return sql.substring(from, to < 0 ? sql.length() : to);
    }

    @Test
    void eightDutyTablesExist() throws IOException {
        String sql = normalized();
        assertThat(sql)
                .contains("create table duty_member")
                .contains("create table duty_channel")
                .contains("create table duty_schedule")
                .contains("create table duty_layer")
                .contains("create table duty_layer_member")
                .contains("create table duty_override")
                .contains("create table duty_notification")
                .contains("create table duty_delivery");
    }

    @Test
    void dutyChannelStoresEnvKeyNamesOnlyNeverWebhookUrl() throws IOException {
        // INV-AM3-3：密钥只经 env 注入，表只存 env 键名——schema 断言防密钥落库
        String block = block(normalized(), "duty_channel");
        assertThat(block)
                .contains("env_key_webhook text not null")
                .contains("platform text not null check (platform in ('dingtalk','wecom'))")
                .contains("priority integer not null check (priority >= 1)")
                .contains("is_fallback boolean not null default false")
                .doesNotContain("webhook_url")
                .doesNotContain("secret text not null");
    }

    @Test
    void dutyNotificationCarriesEpisodeIdentityAndTwoStateReadFlag() throws IOException {
        // 契约①：alert_fingerprint（资源身份）+ episode_id（稳定剧集）+ event_status
        // 进去重指纹；契约④：status 只 UNREAD/READ——READ 不冒充 ACK，无接单语义
        String block = block(normalized(), "duty_notification");
        assertThat(block)
                .contains("episode_id text not null")
                .contains("alert_fingerprint text not null")
                .contains("event_status text not null check (event_status in ('firing','resolved'))")
                .contains("fingerprint text not null")
                .contains("unique (fingerprint)")
                .contains("status text not null default 'unread' check (status in ('unread','read'))")
                .contains("check ((status = 'read') = (read_at is not null))");
        String sql = normalized();
        assertThat(sql).contains("ix_duty_notification_episode");
    }

    @Test
    void dutyDeliverySlotsAreUniquePerPriorityWithFencingShape() throws IOException {
        // 契约③：一通知一优先级槽（降级链无并发重复补投）；六态照搬 notify_outbox
        // 范式（租约+epoch 栅栏+attempt 预算+SENT iff sent_at）
        String block = block(normalized(), "duty_delivery");
        assertThat(block)
                .contains("unique (notification_id, priority)")
                .contains("check (state in ('pending','claimed','sent','retry_wait','dead','suppressed'))")
                .contains("lease_epoch bigint not null default 0")
                .contains("check (attempt_count >= 0 and max_attempts > 0 and attempt_count <= max_attempts)")
                .contains("check ((state = 'sent') = (sent_at is not null))");
        String sql = normalized();
        assertThat(sql)
                .contains("ix_duty_delivery_claim")
                .contains("ix_duty_delivery_lease")
                .contains("ix_duty_delivery_dead");
    }

    @Test
    void overrideWindowAndScheduleVersionShape() throws IOException {
        // 契约⑤：schedule_version 进快照（陈旧可判定）；override 时间窗必须正区间
        assertThat(normalized())
                .contains("schedule_version bigint not null default 1")
                .contains("check (ends_at > starts_at)")
                .contains("rotation text not null check (rotation in ('daily','weekly'))");
    }

    @Test
    void fallbackChannelIsSingleRowByPartialUniqueIndex() throws IOException {
        // 契约⑤：独立应急通道恰好一行（部分唯一索引钉死，多行 fallback 是配置事故）
        assertThat(normalized())
                .contains("where is_fallback");
    }

    @Test
    void roleSplitNotifyAppCannotInsertDeliveryOrDeleteNotification() throws IOException {
        // V9 同构授权面：notify_app 只碰投递面（无 INSERT delivery——降级补投行由
        // control_app watcher 铸造）；control_app 对 duty_notification 无 DELETE
        // （只增台账，AM7 不变量 3）
        String sql = normalized();
        String notifyBlock = sql.substring(sql.indexOf("grant select on duty_notification to notify_app"));
        assertThat(notifyBlock)
                .as("notify_app 对 duty_delivery 只有 select + update（无 insert/delete）")
                .contains("grant select on duty_delivery to notify_app")
                .contains("grant update (")
                .doesNotContain("insert on duty_delivery to notify_app");

        String controlNotification = sql.substring(
                sql.indexOf("grant select, insert on duty_notification to control_app"));
        assertThat(controlNotification)
                .as("control_app 对 duty_notification 只有 select/insert + 已读 CAS 列")
                .startsWith("grant select, insert on duty_notification to control_app")
                .doesNotContain("delete on duty_notification");

        assertThat(sql)
                .contains("revoke all on duty_member, duty_channel, duty_schedule, duty_layer, "
                        + "duty_layer_member, duty_override, duty_notification, duty_delivery from public");
    }
}
