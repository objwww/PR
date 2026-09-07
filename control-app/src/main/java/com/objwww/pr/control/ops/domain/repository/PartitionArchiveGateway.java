package com.objwww.pr.control.ops.domain.repository;

import com.objwww.pr.shared.Digest;

/**
 * 分区归档机械面（M5-19；PG 侧实现 = COPY 导出 + DETACH PARTITION，keep_table
 * 先例参照——detach 后分区表保留为独立表，物理删除属另一道工序）。
 */
public interface PartitionArchiveGateway {

    /**
     * @param partition 分区名（行数/内容摘要/有序导出字节；摘要 = 导出字节 sha256）
     */
    Snapshot snapshot(String partition);

    /** DETACH PARTITION（单语句 autocommit；C-24：default 分区在场禁 CONCURRENTLY；失败即热数据原封） */
    void detach(String partition);

    record Snapshot(String partition, long rowCount, Digest digest, byte[] dump) {
    }
}
