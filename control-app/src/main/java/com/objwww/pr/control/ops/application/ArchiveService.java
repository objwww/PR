package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.repository.ArchiveManifestRepository;
import com.objwww.pr.control.ops.domain.repository.ColdArchiveStore;
import com.objwww.pr.control.ops.domain.repository.PartitionArchiveGateway;
import com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository;
import com.objwww.pr.shared.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 冷归档（M5-19；方案 §3.2 原文面）：导出 → 条数/digest 校验 → DETACH PARTITION
 * （C-24：default 分区在场禁 CONCURRENTLY 形态）。两条铁律：
 * <ul>
 *   <li><b>失败不删热数据</b>——导出/校验任一失败，detach 恒零次（热分区原封），
 *       manifest 留在最后可信状态（EXPORTED = 导出过但未被信任，审计面）；</li>
 *   <li><b>legal hold 阻断</b>（INV-AM5-9）——族域或分区域生效中 hold = 零副作用拒绝。</li>
 * </ul>
 * 幂等：uq(partition_name) 恰一次栅栏——已有 manifest 的分区零二次副作用。
 */
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    public enum Outcome {
        ARCHIVED, ALREADY_ARCHIVED, REJECTED_HOLD, NO_POLICY,
        FAILED_EXPORT, FAILED_VERIFY, FAILED_DETACH
    }

    /**
     * @param state 失败时为 manifest 所处的最后状态（null = 无行）
     */
    public record Result(Outcome outcome, String partition, String exportRef, String state) {
    }

    private final RetentionPolicyRepository policies;
    private final RetentionService retention;
    private final PartitionArchiveGateway gateway;
    private final ColdArchiveStore cold;
    private final ArchiveManifestRepository manifests;

    public ArchiveService(RetentionPolicyRepository policies,
                          RetentionService retention,
                          PartitionArchiveGateway gateway,
                          ColdArchiveStore cold,
                          ArchiveManifestRepository manifests) {
        this.policies = policies;
        this.retention = retention;
        this.gateway = gateway;
        this.cold = cold;
        this.manifests = manifests;
    }

    public Result archive(String table, String partition) {
        // 门 0：无策略 = 不归档（fail-closed）
        if (policies.latestPolicy().isEmpty()) {
            return new Result(Outcome.NO_POLICY, partition, null, null);
        }
        // 门 1：legal hold（INV-AM5-9）——族域/分区域任一生效即零副作用拒绝
        if (retention.cleanupBlocked(table) || retention.cleanupBlocked(table + ":" + partition)) {
            logRejected(table, partition, "legal_hold");
            return new Result(Outcome.REJECTED_HOLD, partition, null, null);
        }
        // 门 2：恰一次栅栏
        if (manifests.findByPartition(partition).isPresent()) {
            return new Result(Outcome.ALREADY_ARCHIVED, partition, null, "ARCHIVED");
        }

        // 导出（失败 = 无 manifest 行 + 热分区原封）
        PartitionArchiveGateway.Snapshot snapshot;
        String exportRef;
        try {
            snapshot = gateway.snapshot(partition);
            exportRef = cold.export(partition, snapshot.dump());
        } catch (RuntimeException e) {
            logRejected(table, partition, "export_failed");
            return new Result(Outcome.FAILED_EXPORT, partition, null, null);
        }
        manifests.insertExported(UUID.randomUUID(), partition, snapshot.rowCount(),
                snapshot.digest().value(), exportRef);

        // 校验（回读 digest 恒等；不过 = manifest 留 EXPORTED，永不 detach）
        try {
            byte[] readBack = cold.readBack(exportRef);
            boolean intact = MessageDigest.isEqual(
                    Digests.sha256Hex(readBack).getBytes(StandardCharsets.UTF_8),
                    snapshot.digest().value().getBytes(StandardCharsets.UTF_8));
            if (!intact || !manifests.advanceState(partition, "EXPORTED", "VERIFIED")) {
                logRejected(table, partition, "verify_failed");
                return new Result(Outcome.FAILED_VERIFY, partition, exportRef, "EXPORTED");
            }
        } catch (RuntimeException e) {
            logRejected(table, partition, "verify_failed");
            return new Result(Outcome.FAILED_VERIFY, partition, exportRef, "EXPORTED");
        }

        // 摘离（keep_table 先例：分区表保留为独立表，物理删除属另一道工序）
        try {
            gateway.detach(partition);
        } catch (RuntimeException e) {
            return new Result(Outcome.FAILED_DETACH, partition, exportRef, "VERIFIED");
        }
        manifests.advanceState(partition, "VERIFIED", "ARCHIVED");
        return new Result(Outcome.ARCHIVED, partition, exportRef, "ARCHIVED");
    }

    private static void logRejected(String table, String partition, String gate) {
        // 仅标识符字段（gate 名），不回显内容
        log.info("CONTROL_ARCHIVE_REJECTED table={} partition={} gate={}", table, partition, gate);
    }
}
