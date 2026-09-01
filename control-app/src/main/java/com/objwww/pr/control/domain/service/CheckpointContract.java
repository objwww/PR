package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.Digest;

import java.util.Objects;

/** checkpoint 可复用性的五分量代码/模型契约。 */
public record CheckpointContract(String promptTemplateVersion,
                                 String findingSchemaVersion,
                                 String mapperContractVersion,
                                 String contextBuilderVersion,
                                 String modelIdentity) {

    public CheckpointContract {
        Objects.requireNonNull(promptTemplateVersion);
        Objects.requireNonNull(findingSchemaVersion);
        Objects.requireNonNull(mapperContractVersion);
        Objects.requireNonNull(contextBuilderVersion);
        Objects.requireNonNull(modelIdentity);
    }

    public Digest digest() {
        return Digest.sha256Of(String.join("\u001f", promptTemplateVersion,
                findingSchemaVersion, mapperContractVersion, contextBuilderVersion, modelIdentity));
    }
}
