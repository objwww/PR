package com.objwww.pr.control.alert.domain.evidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 证据快照仓储端口（AM4 M4-20，V16）。freeze = 快照行+成员行<b>同短事务</b>插入，
 * 无更新路径（迟到证据改不了旧快照）；(run_id, snapshot_digest) 唯一——重复冻结
 * 返回 false（幂等，不重复落成员）。
 */
public interface EvidenceSnapshotRepository {

    /** 成员行（evidence_id 仅做外键/成员身份；不参与 snapshot_digest 计算） */
    record SnapshotMemberRow(UUID evidenceId, String evidenceType, String payloadDigest) {
    }

    /** 冻结结果：true=本次落库；false=同 (run,digest) 快照已存在（幂等跳过） */
    boolean freeze(FrozenSnapshot snapshot, List<SnapshotMemberRow> members);

    Optional<FrozenSnapshot> find(UUID runId, String snapshotDigest);

    /** 冻结成员清单（evidence_id 序稳定；只读——无任何追加/更新路径） */
    List<SnapshotMemberRow> membersOf(UUID snapshotId);

    record FrozenSnapshot(UUID snapshotId, UUID runId, String snapshotDigest,
            long observedGeneration, String configDigest, String toolRegistryDigest,
            String parentSnapshotDigest) {
    }
}
