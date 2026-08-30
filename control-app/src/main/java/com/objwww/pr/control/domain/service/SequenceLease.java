package com.objwww.pr.control.domain.service;

/**
 * 一次原子领取的结果：(sequence, epoch)。两者在同一行锁下取出，
 * 命令不可能带过期 epoch 出生（v2.2 §3-3）。
 */
public record SequenceLease(long sequence, long publicationEpoch) {

    public SequenceLease {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence 必须 >= 1: " + sequence);
        }
        if (publicationEpoch < 0) {
            throw new IllegalArgumentException("publicationEpoch 必须 >= 0: " + publicationEpoch);
        }
    }
}
