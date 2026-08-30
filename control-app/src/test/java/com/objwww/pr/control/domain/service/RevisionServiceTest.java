package com.objwww.pr.control.domain.service;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.RevisionFingerprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * UT-01 / UT-07：revision fingerprint 与 run_key 的确定性。
 */
class RevisionServiceTest {

    private final RevisionService service = new RevisionService();

    private static final long REPO_ID = 123456789L;
    private static final int PR_NUMBER = 42;
    private static final String HEAD = "1111111111111111111111111111111111111111";
    private static final String BASE = "2222222222222222222222222222222222222222";
    private static final String MERGE_BASE = "3333333333333333333333333333333333333333";
    private static final Digest DIFF = Digest.sha256Of("diff-bundle-v1");

    // ---------- UT-01 ----------

    @Test
    void fingerprintIsDeterministicForSameInput() {
        RevisionFingerprint a = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF);
        RevisionFingerprint b = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF);
        assertEquals(a, b);
    }

    @Test
    void fingerprintIgnoresPolicyVersion() {
        // v2.2 §3 拆分语义：policy 不属于代码身份。fingerprint 公式不含 policy 参数，
        // 同一代码身份在不同 policy 世代复用同一 revision 行（policy 变化走 publication_epoch 换届）。
        // 这里用"同代码输入、两次计算"固定该语义：任何 policy 上下文下结果恒定。
        RevisionFingerprint underPolicyV1 = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF);
        RevisionFingerprint underPolicyV2 = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF);
        assertEquals(underPolicyV1, underPolicyV2);
    }

    @Test
    void fingerprintChangesWhenAnyCodeIdentityFieldChanges() {
        RevisionFingerprint base = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF);

        assertNotEquals(base, service.revisionFingerprint(REPO_ID, PR_NUMBER,
                "9999999999999999999999999999999999999999", BASE, MERGE_BASE, DIFF)); // head 变
        assertNotEquals(base, service.revisionFingerprint(REPO_ID, PR_NUMBER,
                HEAD, "8888888888888888888888888888888888888888", MERGE_BASE, DIFF)); // base 变
        assertNotEquals(base, service.revisionFingerprint(REPO_ID, PR_NUMBER,
                HEAD, BASE, "7777777777777777777777777777777777777777", DIFF)); // merge_base 变
        assertNotEquals(base, service.revisionFingerprint(REPO_ID, PR_NUMBER,
                HEAD, BASE, MERGE_BASE, Digest.sha256Of("diff-bundle-v2"))); // diff 变
        assertNotEquals(base, service.revisionFingerprint(REPO_ID, PR_NUMBER + 1, HEAD, BASE, MERGE_BASE, DIFF));
        assertNotEquals(base, service.revisionFingerprint(REPO_ID + 1, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF));
    }

    @Test
    void fingerprintHandlesNullMergeBase() {
        RevisionFingerprint withNull = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, null, DIFF);
        RevisionFingerprint withValue = service.revisionFingerprint(REPO_ID, PR_NUMBER, HEAD, BASE, MERGE_BASE, DIFF);
        assertNotEquals(withNull, withValue);
    }

    // ---------- UT-07 ----------

    @Test
    void runKeyStableAcrossSameTriggerReplay() {
        // webhook 重投兜底（B-3）：同 trigger 重放 → 同 key → 唯一约束拦第二次
        UUID revisionId = UUID.randomUUID();
        Digest first = service.runKey(revisionId, "policy-1", "prompt-1", "toolset-1", "delivery-abc");
        Digest replay = service.runKey(revisionId, "policy-1", "prompt-1", "toolset-1", "delivery-abc");
        assertEquals(first, replay);
    }

    @Test
    void runKeyChangesWhenAnyComponentChanges() {
        UUID revisionId = UUID.randomUUID();
        Digest base = service.runKey(revisionId, "policy-1", "prompt-1", "toolset-1", "delivery-abc");

        assertNotEquals(base, service.runKey(UUID.randomUUID(), "policy-1", "prompt-1", "toolset-1", "delivery-abc"));
        assertNotEquals(base, service.runKey(revisionId, "policy-2", "prompt-1", "toolset-1", "delivery-abc"));
        assertNotEquals(base, service.runKey(revisionId, "policy-1", "prompt-2", "toolset-1", "delivery-abc"));
        assertNotEquals(base, service.runKey(revisionId, "policy-1", "prompt-1", "toolset-2", "delivery-abc"));
        assertNotEquals(base, service.runKey(revisionId, "policy-1", "prompt-1", "toolset-1", "delivery-xyz"));
    }
}
