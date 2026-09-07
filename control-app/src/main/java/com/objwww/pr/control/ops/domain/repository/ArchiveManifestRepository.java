package com.objwww.pr.control.ops.domain.repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 归档 manifest 仓储（V29 archive_manifest；契约面 = partition/row_count/digest/
 * export_ref/state）。state 单向 EXPORTED→VERIFIED→ARCHIVED（advanceState 带
 * from 谓词——DB 面二次推进 0 行）；uq(partition_name) 保证分区归档恰一次。
 */
public interface ArchiveManifestRepository {

    void insertExported(UUID id, String partition, long rowCount, String digestHex,
                        String exportRef);

    Optional<Row> findByPartition(String partition);

    /** state 从 from 推进到 to（不可倒退/不可跳级/终态不可再推：0 行 = false） */
    boolean advanceState(String partition, String from, String to);

    record Row(String partition, long rowCount, String digest, String exportRef,
               String state) {
    }
}
