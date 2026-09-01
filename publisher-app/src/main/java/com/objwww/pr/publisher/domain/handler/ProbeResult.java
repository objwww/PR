package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.shared.Digest;

import java.util.Objects;

/** 远端探针封闭结果；FoundWithContent 从类型上保证内容 digest 必在。 */
public sealed interface ProbeResult permits ProbeResult.FoundNoContent,
        ProbeResult.FoundWithContent, ProbeResult.NotFound, ProbeResult.Unknown {
    record FoundNoContent(String remoteId, String remoteUrl) implements ProbeResult {
        public FoundNoContent { Objects.requireNonNull(remoteId); }
    }
    record FoundWithContent(String remoteId, String remoteUrl, Digest contentDigest) implements ProbeResult {
        public FoundWithContent { Objects.requireNonNull(remoteId); Objects.requireNonNull(contentDigest); }
    }
    record NotFound() implements ProbeResult {}
    record Unknown(String reason) implements ProbeResult {
        public Unknown { Objects.requireNonNull(reason); }
    }
}
