package com.objwww.pr.publisher.domain.model;

/**
 * pr_subject 保序账户行的 fence 视角（T3-A 事务内在行锁下读取）：
 * 当前 publication_epoch + 已解决游标 last_resolved_sequence（评审修正 #5）。
 */
public record SubjectCursor(long publicationEpoch, long lastResolvedSequence) {

    public SubjectCursor {
        if (publicationEpoch < 0 || lastResolvedSequence < 0) {
            throw new IllegalArgumentException("epoch/游标必须 >= 0");
        }
    }
}
