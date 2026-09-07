package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.model.RetentionPolicy;
import com.objwww.pr.control.ops.domain.repository.ColdArchiveStore;
import com.objwww.pr.control.ops.domain.repository.PartitionArchiveGateway;
import com.objwww.pr.control.ops.domain.repository.ArchiveManifestRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-19 冷归档（方案 §3.2 原文面）：导出 → 条数/digest 校验 → DETACH——
 * **失败不删热数据**（任何失败面 detach 恒零次）；legal hold 阻断（INV-AM5-9）；
 * manifest = partition/row_count/digest/export_ref/state 契约面；外部副作用以 fake
 * 冷层替身（真 PG 面见 PostgresArchiveIT，本机跳过）。
 */
class ArchiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final Digest PARTITION_DIGEST = Digest.sha256Of("partition-content");
    private static final byte[] DUMP = "partition-content".getBytes(StandardCharsets.UTF_8);

    private FakeManifests manifests;
    private FakeGateway gateway;
    private FakeColdStore cold;
    private RetentionService retention;
    private FakePolicies policies;
    private ArchiveService service;

    @BeforeEach
    void setUp() {
        policies = new FakePolicies();
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 30,
                "file:///var/archive", false, NOW);
        manifests = new FakeManifests();
        gateway = new FakeGateway();
        cold = new FakeColdStore();
        retention = new RetentionService(policies, name -> List.of(), () -> NOW);
        service = new ArchiveService(policies, retention, gateway, cold, manifests);
    }

    // ---------------- 正路：导出 → 校验 → detach → ARCHIVED ----------------

    @Test
    void happyPathExportsVerifiesDetachesAndArchives() {
        ArchiveService.Result r = service.archive("rca_event", "rca_event_2026_07");

        assertThat(r.outcome()).isEqualTo(ArchiveService.Outcome.ARCHIVED);
        assertThat(gateway.detachCalls).containsExactly("rca_event_2026_07");
        assertThat(cold.exports).hasSize(1);
        ArchiveManifestRepository.Row row = manifests.rows.values().iterator().next();
        assertThat(row.state()).isEqualTo("ARCHIVED");
        assertThat(row.rowCount()).isEqualTo(3);
        assertThat(row.digest()).isEqualTo(PARTITION_DIGEST.value());
        assertThat(row.exportRef()).startsWith("cold://rca_event_2026_07/");
    }

    // ---------------- INV-AM5-9：legal hold 阻断，零副作用 ----------------

    @Test
    void legalHoldBlocksArchiveWithZeroSideEffects() {
        policies.holds.add(new com.objwww.pr.control.ops.domain.model.LegalHold(
                UUID.randomUUID(), "rca_event", "司法取证", "ops-1", NOW, null));

        ArchiveService.Result r = service.archive("rca_event", "rca_event_2026_07");

        assertThat(r.outcome()).isEqualTo(ArchiveService.Outcome.REJECTED_HOLD);
        assertThat(cold.exports).isEmpty();
        assertThat(manifests.rows).isEmpty();
        assertThat(gateway.detachCalls).isEmpty();
    }

    // ---------------- 失败不删热数据 ----------------

    @Test
    void exportFailureKeepsHotDataUntouched() {
        cold.failOnExport = true;

        ArchiveService.Result r = service.archive("rca_event", "rca_event_2026_07");

        assertThat(r.outcome()).isEqualTo(ArchiveService.Outcome.FAILED_EXPORT);
        assertThat(manifests.rows).isEmpty();          // 导出失败 = 无 manifest 行
        assertThat(gateway.detachCalls).isEmpty();     // 热数据原封
    }

    @Test
    void corruptedBackupVerifyFailureKeepsHotDataUntouched() {
        cold.corruptOnReadBack = true;

        ArchiveService.Result r = service.archive("rca_event", "rca_event_2026_07");

        assertThat(r.outcome()).isEqualTo(ArchiveService.Outcome.FAILED_VERIFY);
        assertThat(gateway.detachCalls).isEmpty();     // 校验不过 = 永不 detach
        // manifest 行留在 EXPORTED（审计面：导出过但未被信任）
        assertThat(manifests.rows.values().iterator().next().state()).isEqualTo("EXPORTED");
    }

    @Test
    void noPolicyMeansFailClosed() {
        policies.policy = null;

        ArchiveService.Result r = service.archive("rca_event", "rca_event_2026_07");

        assertThat(r.outcome()).isEqualTo(ArchiveService.Outcome.NO_POLICY);
        assertThat(cold.exports).isEmpty();
        assertThat(gateway.detachCalls).isEmpty();
    }

    // ---------------- 幂等：已归档分区零二次副作用 ----------------

    @Test
    void alreadyArchivedIsIdempotentWithoutSecondExport() {
        manifests.rows.put(UUID.randomUUID(), new ArchiveManifestRepository.Row(
                "rca_event_2026_07", 3, PARTITION_DIGEST.value(), "cold://x", "ARCHIVED"));

        ArchiveService.Result r = service.archive("rca_event", "rca_event_2026_07");

        assertThat(r.outcome()).isEqualTo(ArchiveService.Outcome.ALREADY_ARCHIVED);
        assertThat(cold.exports).isEmpty();
        assertThat(gateway.detachCalls).isEmpty();
    }

    // ---------------- state 单向推进栅栏（DB 面同构语义在 fake 兑现） ----------------

    @Test
    void manifestStateAdvancesOneWayOnly() {
        manifests.insertExported(UUID.randomUUID(), "p", 1, "d", "cold://p");

        assertThat(manifests.advanceState("p", "EXPORTED", "VERIFIED")).isTrue();
        assertThat(manifests.advanceState("p", "VERIFIED", "ARCHIVED")).isTrue();
        assertThat(manifests.advanceState("p", "ARCHIVED", "EXPORTED")).isFalse();   // 不可倒退
        assertThat(manifests.advanceState("p", "VERIFIED", "ARCHIVED")).isFalse();   // 终态不可再推
    }

    // ---------------- fakes ----------------

    /** insert-only 策略链最小仿真（与 RetentionServiceTest.FakePolicies 同构，不共享以保持两测试独立） */
    static final class FakePolicies implements com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository {
        RetentionPolicy policy;
        final List<com.objwww.pr.control.ops.domain.model.LegalHold> holds = new ArrayList<>();

        @Override
        public void insertPolicy(RetentionPolicy p) {
            this.policy = p;
        }

        @Override
        public Optional<RetentionPolicy> latestPolicy() {
            return Optional.ofNullable(policy);
        }

        @Override
        public UUID insertHold(String scope, String reason, String createdBy, Instant at) {
            holds.add(new com.objwww.pr.control.ops.domain.model.LegalHold(
                    UUID.randomUUID(), scope, reason, createdBy, at, null));
            return UUID.randomUUID();
        }

        @Override
        public List<com.objwww.pr.control.ops.domain.model.LegalHold> activeHolds() {
            return holds.stream()
                    .filter(com.objwww.pr.control.ops.domain.model.LegalHold::isActive)
                    .toList();
        }

        @Override
        public boolean releaseHold(UUID holdId, Instant at) {
            return false;
        }
    }

    static final class FakeGateway implements PartitionArchiveGateway {
        final List<String> detachCalls = new ArrayList<>();

        @Override
        public Snapshot snapshot(String partition) {
            return new Snapshot(partition, 3, PARTITION_DIGEST, DUMP);
        }

        @Override
        public void detach(String partition) {
            detachCalls.add(partition);
        }
    }

    static final class FakeColdStore implements ColdArchiveStore {
        final List<String> exports = new ArrayList<>();
        final Map<String, byte[]> stored = new LinkedHashMap<>();
        boolean failOnExport;
        boolean corruptOnReadBack;

        @Override
        public String export(String partition, byte[] content) {
            if (failOnExport) {
                throw new IllegalStateException("冷层不可用");
            }
            String ref = "cold://" + partition + "/" + UUID.randomUUID();
            stored.put(ref, content.clone());
            exports.add(ref);
            return ref;
        }

        @Override
        public byte[] readBack(String ref) {
            byte[] content = stored.get(ref);
            if (corruptOnReadBack) {
                byte[] corrupted = content.clone();
                corrupted[0] ^= 0x7f;   // 损坏包演练
                return corrupted;
            }
            return content.clone();
        }
    }

    static final class FakeManifests implements ArchiveManifestRepository {
        final Map<UUID, ArchiveManifestRepository.Row> rows = new LinkedHashMap<>();

        @Override
        public void insertExported(UUID id, String partition, long rowCount, String digest,
                                   String exportRef) {
            rows.put(id, new ArchiveManifestRepository.Row(
                    partition, rowCount, digest, exportRef, "EXPORTED"));
        }

        @Override
        public Optional<ArchiveManifestRepository.Row> findByPartition(String partition) {
            return rows.values().stream()
                    .filter(r -> r.partition().equals(partition))
                    .findFirst();
        }

        @Override
        public boolean advanceState(String partition, String from, String to) {
            // 单向序与 DB check 同构：EXPORTED→VERIFIED→ARCHIVED，其余一律 0 行
            boolean legal = "EXPORTED".equals(from) && "VERIFIED".equals(to)
                    || "VERIFIED".equals(from) && "ARCHIVED".equals(to);
            if (!legal) {
                return false;
            }
            for (Map.Entry<UUID, ArchiveManifestRepository.Row> e : rows.entrySet()) {
                if (e.getValue().partition().equals(partition)
                        && e.getValue().state().equals(from)) {
                    rows.put(e.getKey(), new ArchiveManifestRepository.Row(
                            e.getValue().partition(), e.getValue().rowCount(),
                            e.getValue().digest(), e.getValue().exportRef(), to));
                    return true;
                }
            }
            return false;
        }
    }
}
