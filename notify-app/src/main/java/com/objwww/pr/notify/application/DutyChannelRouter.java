package com.objwww.pr.notify.application;

import com.objwww.pr.notify.domain.channel.NotificationChannel;
import com.objwww.pr.notify.domain.channel.WebhookChannel;
import com.objwww.pr.notify.domain.service.FencedNotifyExecutor;
import com.objwww.pr.notify.infrastructure.http.JdkWebhookTransport;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 值班渠道路由（M7-14）：duty_channel 行（name/platform/env 键名）→ WebhookChannel
 * 实例。密钥只经 env（INV-AM3-3）：env 键名来自 DB 行，值运行时向 env 解析，
 * <b>不缓存解析结果</b>——密钥轮换（容器重建）后无须改 DB 行；行内容（键名/平台）
 * 按 name 缓存（管理面低频变更，duty 量级下缓存无害）。
 *
 * <p>渠道未配置 env（未注入 webhook）→ {@link WebhookChannel} 构造抛 IllegalArgumentException
 * → 执行器按 channel_not_configured 落 DEAD（C2a 冻结语义）；行不存在/已停用 →
 * 同路径 DEAD——降级由 195 侧 watcher 接管。
 */
public class DutyChannelRouter implements FencedNotifyExecutor.ChannelRouter {

    private record ChannelRow(String platform, String envKeyWebhook, String envKeySecret) {
    }

    private final JdbcClient jdbc;
    private final Environment env;
    private final JdkWebhookTransport transport;
    private final Map<String, ChannelRow> rowCache = new ConcurrentHashMap<>();

    public DutyChannelRouter(JdbcClient jdbc, Environment env,
                             JdkWebhookTransport transport) {
        this.jdbc = jdbc;
        this.env = env;
        this.transport = transport;
    }

    @Override
    public NotificationChannel resolve(String channel) {
        // computeIfAbsent 不接 null（缺席行不缓存：管理面新增行后无须清缓存即生效）
        ChannelRow row = rowCache.get(channel);
        if (row == null) {
            row = loadRow(channel);
            if (row != null) {
                rowCache.put(channel, row);
            }
        }
        if (row == null) {
            throw new IllegalArgumentException(
                    "duty 渠道 " + channel + " 不存在或已停用（行不可解析）");
        }
        return new WebhookChannel(
                WebhookChannel.Platform.valueOf(row.platform().toUpperCase()),
                env.getProperty(row.envKeyWebhook(), ""),
                row.envKeySecret() == null ? null : env.getProperty(row.envKeySecret()),
                transport);
    }

    /** null = 不存在/停用（computeIfAbsent 不缓存 null——缺席行每查一次，duty 量级无害） */
    private ChannelRow loadRow(String name) {
        List<ChannelRow> rows = jdbc.sql("""
                select platform, env_key_webhook, env_key_secret
                from duty_channel where name = :name and enabled
                """).param("name", name)
                .query((rs, i) -> new ChannelRow(rs.getString("platform"),
                        rs.getString("env_key_webhook"), rs.getString("env_key_secret")))
                .list();
        return rows.isEmpty() ? null : rows.get(0);
    }
}
