package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.ScopeSnapshot;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * 授权扩张提案服务（PB-B2，设计基线 §2.2 authorized_scope_expansion）：调查发现
 * 根因在 initial_scope 之外时，域外资源进入授权面的唯一通道——逐条 PENDING_APPROVAL
 * 提案（新 scope snapshot、新 digest 锚位），审批锚随 Phase C 审批面回填。
 *
 * <p>铁律：扩张不是 Agent 自行扩大——
 * <ul>
 *   <li>Resolver miss 的扩张提案<b>不存在</b>（fail-closed：无权威身份即无提案）；</li>
 *   <li>提案态（PENDING_APPROVAL）永不进入计划/执行面——B4 消费模板只认
 *       hasApprovedExpansion（approval_id 锚定，DDL check 强制）；</li>
 *   <li>每次扩张必须重新过 Policy 判定 + 重新审批——本服务只负责"提案"，无放行权。</li>
 * </ul>
 */
public class ScopeExpansionService {

    private final ResourceResolver resolver;
    private final ScopeExpansionLedger ledger;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final String policyVersion;
    private final Clock clock;

    public ScopeExpansionService(ResourceResolver resolver, ScopeExpansionLedger ledger,
            RcaEventAppender events, TransactionOperations tx, String policyVersion,
            Clock clock) {
        this.resolver = Objects.requireNonNull(resolver);
        this.ledger = Objects.requireNonNull(ledger);
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 提案扩张：解析成功 → PENDING_APPROVAL 台账行 + 事件；miss = fail-closed 抛出 */
    public UUID propose(UUID runId, String requestedKey, String reason) {
        Objects.requireNonNull(runId, "runId");
        if (requestedKey == null || requestedKey.isBlank()) {
            throw new IllegalArgumentException("requestedKey 不得为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("扩张必须携带理由（审计锚）");
        }
        return tx.execute(status -> {
            var resource = resolver.resolve(requestedKey)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "RESOLVER_MISS: 扩张提案不存在的资源身份: " + requestedKey));
            ScopeSnapshot snapshot = ScopeSnapshot.of(resource, policyVersion);
            UUID expansionId = ledger.recordPending(runId, requestedKey.trim(),
                    resource.resourceUid(), reason, snapshot.toCanonicalJson(),
                    snapshot.hash(), clock.instant());
            events.append(runId, new RcaEventAppender.EventDraft(UUID.randomUUID(),
                    "SCOPE_EXPANSION_PROPOSED",
                    ScopeExpansionJson.canonicalize(expansionId, requestedKey.trim(),
                            resource.resourceUid(), snapshot.hash())));
            return expansionId;
        });
    }

    /** 事件载荷 canonical 化（固定键序，最小实现） */
    static final class ScopeExpansionJson {
        private ScopeExpansionJson() {
        }

        static String canonicalize(UUID expansionId, String requestedKey, String resourceUid,
                String snapshotHash) {
            return "{\"kind\":\"SCOPE_EXPANSION_PROPOSED\",\"expansion_id\":\"" + expansionId
                    + "\",\"requested_key\":\"" + requestedKey
                    + "\",\"resource_uid\":\"" + resourceUid
                    + "\",\"snapshot_hash\":\"" + snapshotHash + "\"}";
        }
    }
}
