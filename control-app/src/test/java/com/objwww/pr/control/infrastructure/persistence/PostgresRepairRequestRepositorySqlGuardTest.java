package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** RM2-05 静态门：双 Planner 并发领取必须 SKIP LOCKED（跳过而非阻塞），锁内谓词重检不退化。 */
class PostgresRepairRequestRepositorySqlGuardTest {

    @Test
    void lockReadyUsesSkipLockedAndRechecksPredicate() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/objwww/pr/control/infrastructure/persistence/"
                        + "PostgresRepairRequestRepository.java"));
        assertThat(source)
                .contains("FOR UPDATE OF rr SKIP LOCKED")
                // 锁内 EPQ 重检谓词（READ COMMITTED）：state/tier/next_attempt_at
                .contains("rr.state = 'PENDING' AND rr.policy_tier = 'AUTO'")
                .contains("rr.state = 'RETRY_WAIT' AND rr.next_attempt_at <= now()");
    }
}
