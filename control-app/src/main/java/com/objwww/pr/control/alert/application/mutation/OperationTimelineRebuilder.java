package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.OperationStatus;
import com.objwww.pr.control.alert.domain.mutation.OperationStateMachine;
import com.objwww.pr.shared.IllegalTransitionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operation 事件时间线重建器（PB-B5，Phase B 退出准则「全链 dry-run 可从事件重建」
 * 的断言面，§3.4 路线 B 的 mutation 域形态）：OPERATION_* 事件序列 → 逻辑状态视图，
 * 与业务表快照比对——验证审计完整性，不承诺 runtime state 只能从事件产生。
 *
 * <p>重建规则：PREPARED 起点锚 = OPERATION_PREPARED；其后每个状态语义事件映射一次
 * 状态机跃迁（DISPATCHED/ACKNOWLEDGED/VERIFIED/COMPLETED/UNKNOWN/RECONCILING/
 * RETRYABLE/ESCALATED）；事件序跳越中间态 → {@link IllegalTransitionException}
 * （= 审计不完整检测：事件面缺环时重建必炸，不猜不补）。
 */
public final class OperationTimelineRebuilder {

    /** 事件类型 → 状态跃迁（LOCK_RELEASED/SKIPPED 等伴随事件不改变状态） */
    private static final Map<String, OperationStatus> EVENT_TO_STATUS = Map.ofEntries(
            Map.entry("OPERATION_PREPARED", OperationStatus.PREPARED),
            Map.entry("OPERATION_DISPATCHED", OperationStatus.DISPATCHED),
            Map.entry("OPERATION_ACKNOWLEDGED", OperationStatus.ACKNOWLEDGED),
            Map.entry("OPERATION_VERIFIED", OperationStatus.VERIFIED),
            Map.entry("OPERATION_COMPLETED", OperationStatus.COMPLETED),
            Map.entry("OPERATION_UNKNOWN", OperationStatus.UNKNOWN),
            Map.entry("OPERATION_RECONCILING", OperationStatus.RECONCILING),
            Map.entry("OPERATION_RETRYABLE", OperationStatus.RETRYABLE),
            Map.entry("OPERATION_ESCALATED", OperationStatus.ESCALATED),
            // BA-191：真执行确定性判败（DISPATCHED→FAILED_CONFIRMED 直落终态）
            Map.entry("OPERATION_FAILED", OperationStatus.FAILED_CONFIRMED));

    public record Rebuild(Map<UUID, OperationStatus> finalStatuses,
            List<String> unmappedEvents) {
    }

    private OperationTimelineRebuilder() {
    }

    /**
     * @param rows 按 (run seq) 有序的事件行（operationId, eventType）
     */
    public static Rebuild rebuild(List<Row> rows) {
        Map<UUID, OperationStatus> current = new LinkedHashMap<>();
        Map<UUID, List<OperationStatus>> paths = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (Row row : rows) {
            OperationStatus next = EVENT_TO_STATUS.get(row.eventType());
            if (next == null) {
                unmapped.add(row.eventType());
                continue;
            }
            OperationStatus prev = current.get(row.operationId());
            if (prev == null) {
                if (next != OperationStatus.PREPARED) {
                    throw new IllegalTransitionException("重建失败：链起点非 PREPARED 事件 "
                            + row.eventType() + " op=" + row.operationId());
                }
            } else {
                OperationStateMachine.checkTransition(prev, next);
            }
            current.put(row.operationId(), next);
            paths.computeIfAbsent(row.operationId(), k -> new ArrayList<>()).add(next);
        }
        return new Rebuild(current, unmapped);
    }

    public record Row(UUID operationId, String eventType) {
    }
}
