package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.model.LegalHold;
import com.objwww.pr.control.ops.domain.model.RetentionPolicy;
import com.objwww.pr.control.ops.domain.repository.PartitionCatalog;
import com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5-18 保留策略服务门（INV-AM5-9：legal hold 阻断一切清理）：
 * 归档候选 = 月分区且过 hot retention 且未被 hold 阻断；无策略 = 零候选（fail-closed，
 * 不归档比误删安全）；default 分区永不候选（兜底分区承载边界外写入，删它 = 丢数据）。
 */
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    private FakePolicies policies;
    private RetentionService service;

    @BeforeEach
    void setUp() {
        policies = new FakePolicies();
        service = new RetentionService(policies, name -> switch (name) {
            case "rca_event" -> List.of(
                    "rca_event_2026_07", "rca_event_2026_08", "rca_event_2026_09",
                    "rca_event_default");
            case "alert_inbox" -> List.of("alert_inbox_default");
            default -> List.of();
        }, () -> NOW);
    }

    // ---------------- INV-AM5-9：legal hold 阻断一切清理 ----------------

    @Test
    void familyHoldBlocksEveryPartitionOfTheFamily() {
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 30, "file:///cold", false, NOW);
        policies.holds.add(new LegalHold(UUID.randomUUID(), "rca_event", "司法取证",
                "ops-1", NOW.minusSeconds(60), null));

        assertThat(service.archiveCandidates("rca_event")).isEmpty();
        assertThat(service.cleanupBlocked("rca_event")).isTrue();
        assertThat(service.cleanupBlocked("rca_event:rca_event_2026_07")).isTrue();
    }

    @Test
    void partitionHoldBlocksOnlyThatPartition() {
        // hot=1 天：07/08 两分区已过期；hold 只钉 07 → 候选剩 08
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 1, "file:///cold", false, NOW);
        policies.holds.add(new LegalHold(UUID.randomUUID(), "rca_event:rca_event_2026_07",
                "个案取证", "ops-1", NOW.minusSeconds(60), null));

        List<String> candidates = service.archiveCandidates("rca_event");
        assertThat(candidates).containsExactly("rca_event_2026_08");
    }

    @Test
    void releasedHoldBlocksNothing() {
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 30, "file:///cold", false, NOW);
        policies.holds.add(new LegalHold(UUID.randomUUID(), "rca_event", "已解除",
                "ops-1", NOW.minusSeconds(3600), NOW.minusSeconds(60)));

        assertThat(service.cleanupBlocked("rca_event")).isFalse();
        assertThat(service.archiveCandidates("rca_event")).isNotEmpty();
    }

    // ---------------- 候选判定：hot retention / default / 策略缺省 ----------------

    @Test
    void onlyPartitionsPastHotRetentionAreEligible() {
        // hot 30 天，now=09-07：08 月分区在 09-01+30d=10-01 才过期 → 07 月（08-01+30d=08-31）已过
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 30, "file:///cold", false, NOW);

        assertThat(service.archiveCandidates("rca_event"))
                .containsExactly("rca_event_2026_07");
    }

    @Test
    void currentMonthPartitionNeverEligible() {
        // hot=1 天：09 月分区要到 10-01+1d 才过期——当前月分区永不进候选
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 1, "file:///cold", false, NOW);

        assertThat(service.archiveCandidates("rca_event"))
                .containsExactly("rca_event_2026_07", "rca_event_2026_08");
    }

    @Test
    void defaultPartitionIsNeverEligible() {
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 1, "file:///cold", false, NOW);

        assertThat(service.archiveCandidates("rca_event")).doesNotContain("rca_event_default");
    }

    @Test
    void noPolicyMeansZeroCandidatesFailClosed() {
        assertThat(service.archiveCandidates("rca_event")).isEmpty();
    }

    @Test
    void unknownTableFamilyYieldsNothing() {
        policies.policy = new RetentionPolicy(UUID.randomUUID(), 1, 1, "file:///cold", false, NOW);

        assertThat(service.archiveCandidates("nonexistent_table")).isEmpty();
    }

    // ---------------- fake ----------------

    /** insert-only 版本链的最小仿真：latestPolicy 取 policy_version 最大行 */
    static final class FakePolicies implements RetentionPolicyRepository {
        RetentionPolicy policy;
        final List<LegalHold> holds = new ArrayList<>();
        final Map<UUID, Instant> released = new LinkedHashMap<>();

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
            UUID id = UUID.randomUUID();
            holds.add(new LegalHold(id, scope, reason, createdBy, at, null));
            return id;
        }

        @Override
        public List<LegalHold> activeHolds() {
            return holds.stream().filter(LegalHold::isActive).toList();
        }

        @Override
        public boolean releaseHold(UUID holdId, Instant at) {
            if (released.containsKey(holdId)) {
                return false;
            }
            released.put(holdId, at);
            holds.replaceAll(h -> h.id().equals(holdId)
                    ? new LegalHold(h.id(), h.scope(), h.reason(), h.createdBy(), h.createdAt(), at)
                    : h);
            return true;
        }
    }
}
