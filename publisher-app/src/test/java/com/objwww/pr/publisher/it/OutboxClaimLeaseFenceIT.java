package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INC-54 回归（TB-23 排查附带发现）：claim 必须尊重在途租约——
 * PENDING + 未过期 lease_until 的行不得被第二个认领者捞走。
 * 缺口机理：claim 只挂租约不改 state（仍 PENDING），事务提交后行锁释放，
 * SKIP LOCKED 不再遮挡；CLAIM_SQL 的 WHERE 此前不含租约条件，第二 claimer
 * 线程/实例可重复认领同一命令（单 claimer 串行下仅 StaleLease 空转，
 * 多实例即成双发通道）。过期租约必须可回收（崩溃恢复路径）。
 */
class OutboxClaimLeaseFenceIT extends PostgresITBase {

    private ItHarness harness;
    private PublicationStore store;

    @BeforeEach
    void setUp() {
        // 纯 DB 断言，不触网：GitHub stub 地址不被使用
        harness = new ItHarness(casDir, "http://localhost:9");
        store = harness.postgresStore;
    }

    @Test
    void claimRespectsLiveLeaseAndReclaimsAfterExpiry() {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("claim-lease-d1", 1022L, "objwww/mall", 22,
                        "head" + "7".repeat(36), "opened"),
                Digest.sha256Of("claim-lease-diff"), Digest.sha256Of("claim-lease-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1022L, 22)
                .orElseThrow().getId();
        Map<String, Object> payload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "7".repeat(36), "name", "ai-code-review", "finding_count", 0);
        UUID opId = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                "pr:1022#22", CommandType.CREATE_CHECK, payload, List.of()).operationId().value();

        // 第一认领者挂上 30s 租约（state 仍 PENDING，租约在途）
        assertThat(store.claim("lease-a", Duration.ofSeconds(30), 10))
                .anyMatch(c -> c.operationId().value().equals(opId));

        // 第二认领者：租约未过期 → 不得重复认领（修复前此行会被再次捞走）
        assertThat(store.claim("lease-b", Duration.ofSeconds(30), 10))
                .noneMatch(c -> c.operationId().value().equals(opId));

        // 租约过期（崩溃恢复场景，admin 角色拨时钟）→ 必须可回收重领
        adminJdbc.sql("UPDATE outbox_command SET lease_until = now() - interval '1 second'" +
                        " WHERE operation_id = :id")
                .param("id", opId).update();
        assertThat(store.claim("lease-b", Duration.ofSeconds(30), 10))
                .anyMatch(c -> c.operationId().value().equals(opId));
    }
}
