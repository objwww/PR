package com.objwww.pr.control.alert.application.mutation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图台账读/解析推进端口（PB-B2，V114 action_intent + V115 解析列）：
 * 解析推进是单向一次性事实——{@link #markResolved} 只允许在未解析态生效
 * （CAS 守卫，禁重复解析覆盖快照）。
 */
public interface ActionIntentStore {

    record IntentView(UUID intentId, UUID runId, UUID taskId, String actionDigest,
            String toolName, String risk, String resolvedResourceUid,
            String scopeSnapshotHash, String argsJson) {
    }

    Optional<IntentView> findById(UUID intentId);

    /** 解析推进（CAS：resolved_resource_uid IS NULL 才生效）；返回 false = 已解析过 */
    boolean markResolved(UUID intentId, String resourceUid, String snapshotJson,
            String snapshotHash, Instant at);

    /**
     * 计划推进（PB-B4 消费模板内）：OPEN 且已解析 → PLANNED + operation_id 回填
     * （CAS：status='OPEN'）；返回 false = 并发赢家是别人。
     */
    boolean markPlanned(UUID intentId, UUID operationId, Instant at);
}
