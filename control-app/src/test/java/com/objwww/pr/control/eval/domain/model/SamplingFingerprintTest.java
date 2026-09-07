package com.objwww.pr.control.eval.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * SamplingFingerprint VO（M5-04，INV-AM5-3/FUT-40）：两态四字段全记录 + 缺字段
 * 不可进正式门禁 + digest 稳定。钉值 digest 按 BA-22 教训以 Python sha256 对拍
 * 实测校准（canonical 串见 {@code SamplingFingerprint.canonical()}，UTF-8 小写 hex）。
 */
class SamplingFingerprintTest {

    private static final SamplingFingerprint.Sampling FULL =
            new SamplingFingerprint.Sampling(0.2, 0.9, 4096, 42L);

    private static final String PROVIDER = "litellm:qwen3.7-plus@dashscope";
    private static final String MODEL = "qwen3.7-plus";

    /** 钉值：canonical() 全字段样例的 sha256（BA-22 实测校准，改动 canonical 即红） */
    private static final String PINNED_DIGEST =
            "b38e7bde2262812bc4ee2a358decb66c5c9dd97d812d7a77b16c44acb4bf2e80";

    @Test
    void fullFingerprintIsGateEligibleWithStableDigest() {
        var fp = new SamplingFingerprint(FULL, FULL, PROVIDER, MODEL, 1);

        assertThat(fp.isGateEligible()).isTrue();
        assertThat(fp.digest().value())
                .as("固定样例指纹 digest 稳定（BA-22 实测校准钉值）")
                .isEqualTo(PINNED_DIGEST);
        // 同值另建实例 → 同 canonical → 同 digest（可复现锚）
        assertThat(new SamplingFingerprint(
                new SamplingFingerprint.Sampling(0.2, 0.9, 4096, 42L), FULL, PROVIDER, MODEL, 1)
                .digest()).isEqualTo(fp.digest());
        // 任一字段变化 → digest 变化（指纹必须可区分不同采样配置）
        assertThat(new SamplingFingerprint(FULL, FULL, PROVIDER, MODEL, 2).digest())
                .isNotEqualTo(fp.digest());
    }

    @Test
    void missingAnySamplingSlotIsGateIneligible() {
        // INV-AM5-3：请求态/生效态各四字段，缺一即不进正式门禁（fail-closed）
        for (int slot = 0; slot < 8; slot++) {
            var requested = new SamplingFingerprint.Sampling(0.2, 0.9, 4096, 42L);
            var effective = new SamplingFingerprint.Sampling(0.2, 0.9, 4096, 42L);
            if (slot < 4) {
                requested = withNull(requested, slot);
            } else {
                effective = withNull(effective, slot - 4);
            }
            var fp = new SamplingFingerprint(requested, effective, PROVIDER, MODEL, 1);
            assertThat(fp.isGateEligible())
                    .as("采样槽位 %d 缺失必须不可进门禁", slot)
                    .isFalse();
        }
    }

    private static SamplingFingerprint.Sampling withNull(SamplingFingerprint.Sampling s, int field) {
        return switch (field) {
            case 0 -> new SamplingFingerprint.Sampling(null, s.topP(), s.maxTokens(), s.seed());
            case 1 -> new SamplingFingerprint.Sampling(s.temperature(), null, s.maxTokens(), s.seed());
            case 2 -> new SamplingFingerprint.Sampling(s.temperature(), s.topP(), null, s.seed());
            default -> new SamplingFingerprint.Sampling(s.temperature(), s.topP(), s.maxTokens(), null);
        };
    }

    @Test
    void unpairedTrialIsGateIneligible() {
        // trial_no=0 = 非配对试验（单发运行不得宣称正式门禁证据，M5-05 配对试验
        // 接线后经 run 面携带真实轮次）；身份缺失在构造期已拒绝（见构造测试）
        assertThat(new SamplingFingerprint(FULL, FULL, PROVIDER, MODEL, 0).isGateEligible())
                .isFalse();
        // 边界：trial 从 1 起可进门禁
        assertThat(new SamplingFingerprint(FULL, FULL, PROVIDER, MODEL, 1).isGateEligible())
                .isTrue();
    }

    @Test
    void requestedAndEffectiveStatesAreRecordedDistinctly() {
        // qwen3.7-plus thinking 型：生效 max_tokens（思考预算）与请求值可能分化；
        // 两态异值必须同时可见且 digest 可区分
        var requested = new SamplingFingerprint.Sampling(0.7, 0.95, 8192, null);
        var effective = new SamplingFingerprint.Sampling(0.7, 0.95, 8192, 42L);
        var fp = new SamplingFingerprint(requested, effective, PROVIDER, MODEL, 1);

        assertThat(fp.isGateEligible())
                .as("请求态 seed 未声明 = 缺字段，不进门禁")
                .isFalse();
        assertThat(fp.requested().maxTokens()).isEqualTo(8192);
        assertThat(fp.effective().seed()).isEqualTo(42L);
        assertThat(fp.digest().value())
                .isNotEqualTo(new SamplingFingerprint(effective, effective, PROVIDER, MODEL, 1)
                        .digest().value());
    }

    @Test
    void toMapCarriesFixedKeySetMatchingMigrationCheck() {
        var fp = new SamplingFingerprint(FULL, FULL, PROVIDER, MODEL, 3);
        Map<String, Object> map = fp.toMap();

        // 键集与 V23 ck_rca_attempt_fingerprint_keys 一一对应；键序固定（jsonb 形态稳定）
        assertThat(map.keySet()).containsExactly(
                "requested", "effective", "provider_fingerprint", "model", "trial_no");
        assertThat(map.get("provider_fingerprint")).isEqualTo(PROVIDER);
        assertThat(map.get("model")).isEqualTo(MODEL);
        assertThat(map.get("trial_no")).isEqualTo(3L);

        for (String state : new String[] {"requested", "effective"}) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inner = (Map<String, Object>) map.get(state);
            assertThat(inner.keySet()).containsExactly("temperature", "top_p", "max_tokens", "seed");
        }
    }

    @Test
    void constructionRejectsMissingStateIdentityOrNegativeTrial() {
        // null 身份/两态 = NPE（requireNonNull 面）；blank 与负 trial = IAE（requireText 面）
        assertThatNullPointerException()
                .isThrownBy(() -> new SamplingFingerprint(null, FULL, PROVIDER, MODEL, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new SamplingFingerprint(FULL, null, PROVIDER, MODEL, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new SamplingFingerprint(FULL, FULL, null, MODEL, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new SamplingFingerprint(FULL, FULL, PROVIDER, null, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SamplingFingerprint(FULL, FULL, " ", MODEL, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SamplingFingerprint(FULL, FULL, PROVIDER, "", 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SamplingFingerprint(FULL, FULL, PROVIDER, MODEL, -1));
    }
}
