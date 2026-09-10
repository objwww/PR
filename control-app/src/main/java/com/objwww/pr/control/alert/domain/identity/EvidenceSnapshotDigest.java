package com.objwww.pr.control.alert.domain.identity;

/**
 * 输出证据集身份 digest（EX-A0 F04 三身份之一）：冻结证据快照的内容身份
 * （EvidenceSnapshotBuilder 聚合摘要）。持久列 = rca_evidence_snapshot.snapshot_digest
 * （V16）。Claim 绑定与本身份；与输入侧 {@link InvestigationInputDigest} 无转换关系。
 */
public record EvidenceSnapshotDigest(String value) {

    public EvidenceSnapshotDigest {
        Hex64.require(value, "evidenceSnapshotDigest");
    }

    public String hex() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
