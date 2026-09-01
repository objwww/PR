package com.objwww.pr.control.domain.service;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointContractTest {

    private static final CheckpointContract BASE = new CheckpointContract(
            "prompt-v1", "schema-v1", "mapper-v1", "context-v1", "provider/model/v1");

    @Test
    void everyComponentChangesDigest() {
        assertChanged(c -> new CheckpointContract("prompt-v2", c.findingSchemaVersion(),
                c.mapperContractVersion(), c.contextBuilderVersion(), c.modelIdentity()));
        assertChanged(c -> new CheckpointContract(c.promptTemplateVersion(), "schema-v2",
                c.mapperContractVersion(), c.contextBuilderVersion(), c.modelIdentity()));
        assertChanged(c -> new CheckpointContract(c.promptTemplateVersion(), c.findingSchemaVersion(),
                "mapper-v2", c.contextBuilderVersion(), c.modelIdentity()));
        assertChanged(c -> new CheckpointContract(c.promptTemplateVersion(), c.findingSchemaVersion(),
                c.mapperContractVersion(), "context-v2", c.modelIdentity()));
        assertChanged(c -> new CheckpointContract(c.promptTemplateVersion(), c.findingSchemaVersion(),
                c.mapperContractVersion(), c.contextBuilderVersion(), "provider/model/v2"));
    }

    private static void assertChanged(UnaryOperator<CheckpointContract> change) {
        assertThat(change.apply(BASE).digest()).isNotEqualTo(BASE.digest());
    }
}
