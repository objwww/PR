package com.objwww.pr.control.ops.duty.application;

import com.objwww.pr.control.alert.domain.model.AlertGroupSummary;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.control.ops.duty.domain.DutyAlertIdentity;
import com.objwww.pr.control.ops.duty.domain.DutyResolver;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 值班派发服务（M7-13；AM7 §3）：事件 → 一事务落 duty_notification + 首行
 * duty_delivery（原子对）。不做降级扫描（{@link DutyFallbackWatcher} 职责）、
 * 不触网（notify-app 执行投递）。
 *
 * <p>时钟 = {@code Supplier<Instant>}（B-41 律：传值会冻结 bean 创建时刻）。
 * 空链诚实记账：无通道可投（连 fallback 都未配）时通知行照落（台账不丢）、零投递行
 * + DUTY_DISPATCH_NO_CHANNEL 结构化事件——「告警无人可发」不允许静默消失（不变量 4）。
 */
public class DutyDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DutyDispatchService.class);

    private final DutyStore store;
    private final Supplier<Instant> clock;

    public DutyDispatchService(DutyStore store, Supplier<Instant> clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    /** RCA_SYSTEM 腿（AlertWebhookController ROUTED_ONCALL 分支消费） */
    public DutyStore.DispatchOutcome dispatchSystem(AlertGroupSummary group) {
        Objects.requireNonNull(group);
        DutyAlertIdentity identity = DutyAlertIdentity.of("RCA_SYSTEM",
                group.commonLabels(), group.groupKey(), group.startsAt(), group.status());
        String title = "值班告警 " + String.join(",", group.alertnames())
                + " [" + group.status() + "]";
        String body = "receiver=" + group.receiver()
                + " groupKey=" + group.groupKey()
                + " alerts=" + group.alertCount()
                + " labels=" + group.commonLabels();
        return dispatch("RCA_SYSTEM", identity, group.status(), group.severityOf(),
                title, body);
    }

    /** MANUAL 测试入口（管理页「发送测试通知」）：每次全新 episode，不参与去重 */
    public DutyStore.DispatchOutcome dispatchManual(String title, String body, String severity) {
        String nonce = UUID.randomUUID().toString();
        DutyAlertIdentity identity = new DutyAlertIdentity(nonce, nonce, nonce);
        return dispatch("MANUAL", identity, "firing", severity, title, body);
    }

    /**
     * 127 duty-adapter 回写腿（M7-17）：GATUS 事件只落台账行（无 delivery——127 已
     * 直发 webhook）。身份/payload 铸造与 RCA_SYSTEM 同律——fingerprint 算法单源。
     */
    public DutyStore.DispatchOutcome dispatchExternal(String source,
                                                      Map<String, String> commonLabels,
                                                      String groupKey, Instant triggeredAt,
                                                      String eventStatus, String severity,
                                                      String title, String body) {
        DutyAlertIdentity identity = DutyAlertIdentity.of(source, commonLabels, groupKey,
                triggeredAt, eventStatus);
        return store.insertExternalNotification(new DutyStore.NewNotification(source,
                identity.episodeId(), identity.alertFingerprint(), eventStatus, severity,
                title, body, payloadJson(source, severity, title, body, identity.fingerprint()),
                identity.fingerprint()));
    }

    private DutyStore.DispatchOutcome dispatch(String source, DutyAlertIdentity identity,
                                               String eventStatus, String severity,
                                               String title, String body) {
        DutyScheduleSnapshot snapshot = store.loadSnapshot();
        DutyResolver.Resolution resolved = DutyResolver.resolve(clock.get(), snapshot);
        DutyScheduleSnapshot.Channel first = resolved.channelChain().isEmpty()
                ? null : resolved.channelChain().get(0);

        DutyStore.DispatchOutcome outcome = store.insertNotificationWithFirstDelivery(
                new DutyStore.NewNotification(source, identity.episodeId(),
                        identity.alertFingerprint(), eventStatus, severity, title, body,
                        payloadJson(source, severity, title, body, identity.fingerprint()),
                        identity.fingerprint()),
                first);

        if (first == null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("notification_id", outcome.notificationId().toString());
            fields.put("source", source);
            fields.put("episode_id", identity.episodeId());
            fields.put("reason", "no_channel_configured");
            StructuredLog.event(log, "DUTY_DISPATCH_NO_CHANNEL", fields);
        }
        return outcome;
    }

    /**
     * notify-app 渲染契约（DutyNotificationRenderer 白名单面）：operation_id 自
     * fingerprint 确定性派生——AM 重发撞 fingerprint 去重后行不变，operation_id 随行
     * 稳定（重复投递可检测）；type=duty 区分报告 payload。
     */
    private static String payloadJson(String source, String severity, String title,
                                      String body, String fingerprint) {
        String operationId = UUID.nameUUIDFromBytes(
                fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return "{\"type\":\"duty\",\"operation_id\":\"" + operationId
                + "\",\"source\":\"" + escape(source)
                + "\",\"severity\":\"" + escape(severity)
                + "\",\"title\":\"" + escape(title) + "\",\"body\":\"" + escape(body) + "\"}";
    }

    private static String escape(String s) {
        String safe = s == null ? "" : s;
        return safe.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
