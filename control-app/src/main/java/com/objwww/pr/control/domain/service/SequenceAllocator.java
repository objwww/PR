package com.objwww.pr.control.domain.service;

import java.util.UUID;

/**
 * sequence/epoch 原子分配端口（domain 端口，实现在 infrastructure）。
 * 语义 = 对 pr_subject 行 UPDATE ... SET next_outbox_sequence=next_outbox_sequence+1
 * ... RETURNING next_outbox_sequence-1, publication_epoch。
 * 不用 MAX()+1、不用 Redis 锁（方案 §3.1）。
 */
public interface SequenceAllocator {

    /**
     * 在调用方事务内原子领取 (sequence, epoch)。行锁持有至事务结束，
     * 保证同一 PR 的命令序与 epoch 严格单调（I8）。
     */
    SequenceLease allocate(UUID prSubjectId);
}
