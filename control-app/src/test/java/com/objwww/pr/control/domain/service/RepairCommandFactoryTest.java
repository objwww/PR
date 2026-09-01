package com.objwww.pr.control.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import com.objwww.pr.shared.RepairRequestState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepairCommandFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RepairCommandFactory factory = new RepairCommandFactory(mapper);

    @Test
    void checkRepairMergesLatestTerminalStateOntoCreateIdentity() throws Exception {
        RepairCandidate candidate = candidate(PublicationResourceType.CHECK_RUN, CommandType.UPDATE_CHECK);
        byte[] base = mapper.writeValueAsBytes(Map.of(
                "operation_id", UUID.randomUUID().toString(), "installation_id", 7,
                "repo", "octo/demo", "head_sha", "abc", "name", "ai-code-review",
                "remote_id", "old", "remote_url", "https://old"));
        byte[] latest = mapper.writeValueAsBytes(Map.of(
                "operation_id", UUID.randomUUID().toString(), "installation_id", 7,
                "repo", "octo/demo", "check_run_id", "99", "status", "completed",
                "conclusion", "success"));

        RepairCommandFactory.Prepared prepared = factory.prepare(candidate, latest, base);
        Map<String, Object> payload = mapper.readValue(prepared.payload(), new TypeReference<>() {});

        assertThat(prepared.commandType()).isEqualTo(CommandType.CREATE_CHECK);
        assertThat(payload).containsEntry("head_sha", "abc")
                .containsEntry("name", "ai-code-review")
                .containsEntry("status", "completed")
                .containsEntry("conclusion", "success")
                .doesNotContainKeys("remote_id", "remote_url", "check_run_id");
        assertThat(payload.get("operation_id")).isEqualTo(prepared.operationId().toString());
        assertThat(payload.get("repair_of_resource_id")).isEqualTo(candidate.resourceId().toString());
    }

    @Test
    void reviewRepairRegeneratesMarkerFromNewOperationId() throws Exception {
        RepairCandidate candidate = candidate(PublicationResourceType.REVIEW, CommandType.PUBLISH_REVIEW);
        byte[] latest = mapper.writeValueAsBytes(Map.of(
                "operation_id", UUID.randomUUID().toString(), "installation_id", 7,
                "repo", "octo/demo", "pr_number", 12, "commit_id", "abc",
                "marker", "<!-- ai-review:old -->"));

        RepairCommandFactory.Prepared prepared = factory.prepare(candidate, latest, latest);
        Map<String, Object> payload = mapper.readValue(prepared.payload(), new TypeReference<>() {});

        assertThat(payload.get("marker"))
                .isEqualTo("<!-- ai-review:" + prepared.operationId() + " -->");
    }

    @Test
    void rawAddressOrSecretFieldFailsClosed() throws Exception {
        RepairCandidate candidate = candidate(PublicationResourceType.REVIEW, CommandType.PUBLISH_REVIEW);
        byte[] poisoned = mapper.writeValueAsBytes(Map.of(
                "repo", "octo/demo", "url", "https://attacker", "token", "secret"));

        assertThatThrownBy(() -> factory.prepare(candidate, poisoned, poisoned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repair desired payload 非法");
    }

    private static RepairCandidate candidate(PublicationResourceType resourceType, CommandType commandType) {
        UUID revision = UUID.randomUUID();
        Digest digest = Digest.sha256Of("payload");
        return new RepairCandidate(UUID.randomUUID(), RepairPolicyTier.AUTO, RepairRequestState.PENDING,
                0, 3, UUID.randomUUID(), resourceType, UUID.randomUUID(), revision, revision,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), commandType,
                "pr:test", "policy", digest, digest);
    }
}
