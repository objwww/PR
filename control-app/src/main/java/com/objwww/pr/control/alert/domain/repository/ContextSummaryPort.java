package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.agent.ContextSummary;

import java.util.Optional;
import java.util.UUID;

/**
 * R11 上下文摘要端口（V92 rca_context_summary，不可变档）：uq(run, task,
 * source_snapshot_digest) 冲突返回既有行（CAS 提交语义——并发同源双写一胜一拒，
 * 同源重放幂等不漂移已提交面）。
 */
public interface ContextSummaryPort {

    /** CAS 提交：同 (run, task, source) 已有摘要 → 返回既有行（候选丢弃） */
    ContextSummary append(ContextSummary candidate);

    /** 同源已提交摘要（幂等重放判定锚） */
    Optional<ContextSummary> findBySource(UUID runId, UUID taskId, String sourceDigest);

    /** 当前指针：最近提交摘要（created_at 最大）；无 → empty */
    Optional<ContextSummary> latestByTask(UUID runId, UUID taskId);

    /** Run 级已提交计数（max-per-run 闸的读取面；跨任务共享同一 Run 预算语义） */
    long countByRun(UUID runId);

    /** 任务级已提交计数（COMPACTION 动作序保留段取号基数） */
    long countByTask(UUID runId, UUID taskId);
}
