package com.objwww.pr.control.domain.service;

import com.objwww.pr.control.domain.model.StepCheckpoint;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointContractChangeReasonTest {

    private static final CheckpointContract BASE = new CheckpointContract(
            "prompt-v1", "schema-v1", "mapper-v1", "context-v1", "model-v1");

    @ParameterizedTest
    @MethodSource("changes")
    void identifiesExactChangedComponent(CheckpointContract current, String reason) {
        assertThat(CheckpointResumeService.contractChange(checkpoint(BASE), current)).isEqualTo(reason);
    }

    static Stream<Arguments> changes() {
        return Stream.of(
                Arguments.of(new CheckpointContract("prompt-v2", "schema-v1", "mapper-v1", "context-v1", "model-v1"), "CONTRACT_CHANGED:prompt"),
                Arguments.of(new CheckpointContract("prompt-v1", "schema-v2", "mapper-v1", "context-v1", "model-v1"), "CONTRACT_CHANGED:schema"),
                Arguments.of(new CheckpointContract("prompt-v1", "schema-v1", "mapper-v2", "context-v1", "model-v1"), "CONTRACT_CHANGED:mapper"),
                Arguments.of(new CheckpointContract("prompt-v1", "schema-v1", "mapper-v1", "context-v2", "model-v1"), "CONTRACT_CHANGED:context"),
                Arguments.of(new CheckpointContract("prompt-v1", "schema-v1", "mapper-v1", "context-v1", "model-v2"), "CONTRACT_CHANGED:model_identity"),
                Arguments.of(BASE, null));
    }

    private static StepCheckpoint checkpoint(CheckpointContract contract) {
        return new StepCheckpoint(UUID.randomUUID(), UUID.randomUUID(), StepCheckpoint.REVIEW_OUTCOME,
                Digest.sha256Of("output"), Digest.sha256Of("model"), contract.digest(),
                contract.promptTemplateVersion(), contract.findingSchemaVersion(),
                contract.mapperContractVersion(), contract.contextBuilderVersion(),
                contract.modelIdentity(), 1, 1, Instant.now());
    }
}
