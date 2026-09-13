package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.CompactionAttempt;

import java.util.Optional;
import java.util.UUID;

/**
 * 压缩尝试台账口（CL-07，V102）：insert-if-absent 单写者语义（并发预留一胜一
 * 拒，败者读胜者行）；状态推进一律 CAS（fromState 不匹配=他人已终态化，返回
 * false，调用方读最新行收敛——不覆盖既成结果）。
 */
public interface CompactionAttemptPort {

    /** 预留：无冲突=入库并返回入参；唯一键冲突=返回既有胜者行（入参被丢弃） */
    CompactionAttempt insertIfAbsent(CompactionAttempt candidate);

    /** 状态 CAS 终态化（settledAt 由实现侧填；COMMITTED 须携带 summaryId） */
    boolean casState(UUID id, String fromState, String toState,
            String errorCode, UUID summaryId);

    Optional<CompactionAttempt> findById(UUID id);
}
