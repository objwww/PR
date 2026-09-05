package com.objwww.pr.notify.application;

import com.objwww.pr.notify.domain.channel.NotificationChannel;
import com.objwww.pr.notify.domain.channel.ValidateOnlyChannel;
import com.objwww.pr.notify.domain.channel.WebhookChannel;
import com.objwww.pr.notify.domain.service.FencedNotifyExecutor;
import com.objwww.pr.notify.domain.service.NotificationRenderer;
import com.objwww.pr.notify.infrastructure.http.JdkWebhookTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * notify-app 装配（M3-20~23）：单 worker + 三档渠道路由。
 *
 * <p>渠道 webhook/secret <b>仅 env 注入</b>（INV-AM3-3）：约定键
 * {@code NOTIFY_CHANNEL_<大写渠道名>_WEBHOOK} / {@code _SECRET}（钉钉加签可配）。
 * 未配置 webhook 的 TEST/LIVE 渠道在装配期 fail-fast（拒绝无凭证创建）；未列入
 * app.notify.channels 的 outbox 渠道名在执行期按确定性 DEAD 处理，不静默丢弃。
 */
@Configuration
public class NotifyConfig {

    @Bean
    public JdbcClient notifyJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    public com.objwww.pr.notify.domain.port.NotifyOutboxStore notifyOutboxStore(
            JdbcClient jdbc) {
        return new com.objwww.pr.notify.infrastructure.persistence.PostgresNotifyOutboxStore(
                jdbc);
    }

    @Bean
    public NotificationRenderer notificationRenderer(
            @Value("${app.notify.max-field-chars:500}") int maxFieldChars,
            @Value("${app.notify.max-total-chars:3800}") int maxTotalChars) {
        return new NotificationRenderer(maxFieldChars, maxTotalChars);
    }

    @Bean
    public FencedNotifyExecutor.ChannelRouter channelRouter(
            @Value("${app.notify.channel-names:}") String channelNames,
            Environment env, JdkWebhookTransport transport) {
        Map<String, NotificationChannel> byName = new HashMap<>();
        for (String raw : channelNames.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            String tier = required(env, "app.notify.channels." + name + ".tier").toUpperCase();
            String platform = env.getProperty(
                    "app.notify.channels." + name + ".platform", "dingtalk");
            switch (tier) {
                case "VALIDATE_ONLY" -> byName.put(name, new ValidateOnlyChannel());
                case "TEST_CHANNEL", "LIVE" -> byName.put(name, new WebhookChannel(
                        WebhookChannel.Platform.valueOf(platform.toUpperCase()),
                        env.getProperty(webhookEnv(name), ""),
                        env.getProperty(secretEnv(name)),
                        transport));
                default -> throw new IllegalStateException(
                        "渠道 " + name + " tier 非法: " + tier + "（三档冻结 M3-22）");
            }
        }
        return byName::get;
    }

    private static String webhookEnv(String channel) {
        return "NOTIFY_CHANNEL_" + channel.toUpperCase() + "_WEBHOOK";
    }

    private static String secretEnv(String channel) {
        return "NOTIFY_CHANNEL_" + channel.toUpperCase() + "_SECRET";
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺配置 " + key + "（装配期 fail-fast）");
        }
        return value;
    }

    @Bean
    public FencedNotifyExecutor.Backoff notifyBackoff(
            @Value("${app.notify.retry-base-seconds:30}") long baseSeconds,
            @Value("${app.notify.retry-cap-seconds:1800}") long capSeconds) {
        return (failedAttempts, from, retryAfterSeconds) -> {
            if (retryAfterSeconds > 0) {
                return from.plusSeconds(Math.min(retryAfterSeconds, 3600));
            }
            long delay = Math.min(capSeconds, baseSeconds << Math.min(failedAttempts - 1, 10));
            return from.plusSeconds(delay);
        };
    }

    @Bean
    public FencedNotifyExecutor fencedNotifyExecutor(
            com.objwww.pr.notify.domain.port.NotifyOutboxStore store,
            NotificationRenderer renderer,
            FencedNotifyExecutor.ChannelRouter router,
            FencedNotifyExecutor.Backoff backoff) {
        return new FencedNotifyExecutor(store, renderer, router, backoff, Instant.now());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public NotifyOutboxClaimer notifyOutboxClaimer(
            com.objwww.pr.notify.domain.port.NotifyOutboxStore store,
            FencedNotifyExecutor executor,
            @Value("${app.notify.owner-id:}") String ownerId,
            @Value("${app.notify.lease-seconds:60}") long leaseSeconds,
            @Value("${app.notify.batch-size:10}") int batchSize,
            @Value("${app.notify.idle-sleep-ms:2000}") long idleSleepMs,
            @Value("${app.notify.error-sleep-ms:10000}") long errorSleepMs)
            throws java.net.UnknownHostException {
        String owner = ownerId == null || ownerId.isBlank()
                ? InetAddress.getLocalHost().getHostName() : ownerId;
        return new NotifyOutboxClaimer(store, executor, owner,
                java.time.Duration.ofSeconds(leaseSeconds), batchSize,
                idleSleepMs, errorSleepMs);
    }
}
