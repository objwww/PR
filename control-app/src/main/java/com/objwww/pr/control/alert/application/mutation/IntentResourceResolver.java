package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.ScopeSnapshot;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 意图资源解析推进服务（PB-B2，设计基线 §1 第三步 Authoritative Resource Resolve）：
 * 意图 OPEN → 请求键经权威 Resolver 解析 → scope snapshot 锚写入意图（同事务事件）。
 *
 * <p>语义铁律：
 * <ul>
 *   <li><b>fail-closed</b>——Resolver miss 落 INTENT_RESOLVE_FAILED 事件，意图保持
 *       未解析（永不进入授权/计划面），不猜不补；</li>
 *   <li><b>一次性</b>——已解析意图禁重复解析（快照锚不可覆盖，CAS 守卫）；</li>
 *   <li>快照锚（hash）随事件落 run 事件账本——授权事实读路径（B 组不变量）。</li>
 * </ul>
 */
public class IntentResourceResolver {

    /** 解析结果：resolved（带锚）或 failed（fail-closed，无任何身份产出） */
    public record Outcome(boolean resolved, String resourceUid, String snapshotHash) {
    }

    private final ResourceResolver resolver;
    private final ActionIntentStore store;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final String policyVersion;
    private final Clock clock;

    public IntentResourceResolver(ResourceResolver resolver, ActionIntentStore store,
            RcaEventAppender events, TransactionOperations tx, String policyVersion,
            Clock clock) {
        this.resolver = Objects.requireNonNull(resolver);
        this.store = Objects.requireNonNull(store);
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.clock = Objects.requireNonNull(clock);
    }

    public Outcome resolveAndRecord(UUID intentId, String requestedKey) {
        return tx.execute(status -> {
            ActionIntentStore.IntentView intent = store.findById(intentId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "意图不存在: " + intentId));
            if (intent.resolvedResourceUid() != null) {
                throw new IllegalStateException("意图已解析（快照锚不可覆盖）: " + intentId);
            }
            return resolver.resolve(requestedKey)
                    .map(resource -> {
                        ScopeSnapshot snapshot = ScopeSnapshot.of(resource, policyVersion);
                        boolean applied = store.markResolved(intentId, resource.resourceUid(),
                                snapshot.toCanonicalJson(), snapshot.hash(), clock.instant());
                        if (!applied) {
                            throw new IllegalStateException(
                                    "解析推进 CAS 失利（并发重复解析）: " + intentId);
                        }
                        events.append(intent.runId(), draft(intent.runId(),
                                "INTENT_RESOURCE_RESOLVED", Map.of(
                                        "intent_id", intentId.toString(),
                                        "requested_key", requestedKey,
                                        "resource_uid", resource.resourceUid(),
                                        "snapshot_hash", snapshot.hash())));
                        return new Outcome(true, resource.resourceUid(), snapshot.hash());
                    })
                    .orElseGet(() -> {
                        // fail-closed：无权威身份 = 无授权可能；事件留痕，意图保持未解析
                        events.append(intent.runId(), draft(intent.runId(),
                                "INTENT_RESOLVE_FAILED", Map.of(
                                        "intent_id", intentId.toString(),
                                        "requested_key", requestedKey,
                                        "reason", "RESOLVER_MISS")));
                        return new Outcome(false, null, null);
                    });
        });
    }

    private static RcaEventAppender.EventDraft draft(UUID runId, String type,
            Map<String, String> payload) {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("kind", type);
        full.putAll(payload);
        return new RcaEventAppender.EventDraft(UUID.randomUUID(), type,
                InternalSnapshotJson.canonicalize(full));
    }

    /** 事件载荷 canonical 化（复用 scope/decision 的固定序风格，最小键序实现） */
    static final class InternalSnapshotJson {
        private InternalSnapshotJson() {
        }

        static String canonicalize(Map<String, Object> payload) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(entry.getKey()).append("\":\"");
                Object value = entry.getValue();
                json.append(value instanceof String s ? s.replace("\"", "\\\"") : value);
                json.append('"');
            }
            return json.append('}').toString();
        }
    }
}
