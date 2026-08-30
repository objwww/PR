package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import com.objwww.pr.shared.RevisionFingerprint;

import java.util.Objects;
import java.util.UUID;

/**
 * Revision 领域服务：fingerprint 与 run_key 的唯一定义点。
 * 纯函数，不触碰 Outbox（v2.1 修订三）。拼接用 '|' 分段防前缀歧义。
 */
public final class RevisionService {

    private static final String SEP = "|";

    /**
     * revision_fingerprint = SHA256(repoId | prNumber | headSha | baseSha | mergeBaseSha | diffDigest)。
     * 纯代码身份：不含 policy/prompt/toolset——policy 变化不影响 fingerprint（v2.2 §3，UT-01）；
     * policy 变化走 publication_epoch 换届，不是换 revision。
     */
    public RevisionFingerprint revisionFingerprint(long githubRepositoryId, int prNumber,
                                                   String headSha, String baseSha,
                                                   String mergeBaseSha, Digest diffDigest) {
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(baseSha, "baseSha");
        Objects.requireNonNull(diffDigest, "diffDigest");
        return new RevisionFingerprint(Digests.sha256Hex(String.join(SEP,
                Long.toString(githubRepositoryId),
                Integer.toString(prNumber),
                headSha,
                baseSha,
                mergeBaseSha == null ? "" : mergeBaseSha,
                diffDigest.value())));
    }

    /**
     * run_key = SHA256(revisionId | policyVersion | promptVersion | toolsetVersion | triggerKey)。
     * webhook 重投幂等兜底（B-3）：同 trigger 重放 → 同 key → uq_review_run_key 拒第二次 INSERT（UT-07）。
     */
    public Digest runKey(UUID revisionId, String policyVersion, String promptVersion,
                         String toolsetVersion, String triggerKey) {
        Objects.requireNonNull(revisionId, "revisionId");
        return Digest.sha256Of(String.join(SEP,
                revisionId.toString(),
                Objects.requireNonNull(policyVersion),
                Objects.requireNonNull(promptVersion),
                Objects.requireNonNull(toolsetVersion),
                Objects.requireNonNull(triggerKey)));
    }
}
