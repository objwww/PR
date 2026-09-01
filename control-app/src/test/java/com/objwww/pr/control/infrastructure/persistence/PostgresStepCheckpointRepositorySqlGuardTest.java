package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** I25 静态门：checkpoint 写不能退化成应用侧先查租约再写。 */
class PostgresStepCheckpointRepositorySqlGuardTest {

    @Test
    void upsertAtomicallyChecksOwnerEpochStateAndDbLeaseTime() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/objwww/pr/control/infrastructure/persistence/"
                        + "PostgresStepCheckpointRepository.java"));
        assertThat(source)
                .contains("wi.lease_owner = :leaseOwner")
                .contains("wi.lease_epoch = :leaseEpoch")
                .contains("wi.state = 'LEASED'")
                .contains("now() <= wi.lease_until")
                .contains("ON CONFLICT (step_id, checkpoint_key) DO UPDATE")
                .doesNotContain("Instant.now()");
    }

    /** RM2-01 静态门：V4 五分量列必须在 INSERT 与 ON CONFLICT 两段都写、find 全量读回。 */
    @Test
    void upsertAndFindCoverAllFiveContractComponentColumns() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/objwww/pr/control/infrastructure/persistence/"
                        + "PostgresStepCheckpointRepository.java"));
        for (String column : new String[]{"prompt_template_version", "finding_schema_version",
                "mapper_contract_version", "context_builder_version", "model_identity"}) {
            // INSERT 列清单 + ON CONFLICT SET + find SELECT 三处出现
            assertThat(source.split(column, -1).length - 1)
                    .as("列 %s 在 INSERT/ON CONFLICT/SELECT 三处贯通", column)
                    .isGreaterThanOrEqualTo(3);
            assertThat(source).contains(column + " = EXCLUDED." + column);
        }
    }
}
