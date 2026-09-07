package com.objwww.pr.control.eval.domain.model;

import com.objwww.pr.shared.Digest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模型采样指纹（M5-04，FUT-40/INV-AM5-3）：temperature/top_p/max_tokens/seed 的
 * <b>请求态 + 生效态</b>两态全记录 + provider fingerprint + model + trial 序号。
 *
 * <p>门禁纪律：{@link #isGateEligible()} = 两态四字段全非空且身份齐全、trial ≥ 1——
 * 缺任一字段不得进入正式门禁（INV-AM5-3，fail-closed）。生效 seed 无 provider
 * 回传渠道时恒为 null（V10「诚实留空」惯例）→ 该组合自然不进门禁，直到回传链路
 * 接通；trial_no = 0 表示非配对试验（单发运行不得宣称正式门禁证据）。
 *
 * <p>{@link #canonical()} 为固定字段序的规范串（digest 可复现锚）；{@link #toMap()}
 * 为 V23 jsonb 落库形态（键集与 ck_rca_attempt_fingerprint_keys 一一对应，键序固定）。
 * domain 零框架：序列化由 infrastructure 完成。
 */
public record SamplingFingerprint(Sampling requested,
                                  Sampling effective,
                                  String providerFingerprint,
                                  String model,
                                  long trialNo) {

    /** 单态采样四字段：provider 未支持/未声明时字段为 null（诚实留空，但门禁不可过） */
    public record Sampling(Double temperature, Double topP, Integer maxTokens, Long seed) {
    }

    public SamplingFingerprint {
        Objects.requireNonNull(requested, "requested 态不得为 null");
        Objects.requireNonNull(effective, "effective 态不得为 null");
        DatasetVersion.requireText(providerFingerprint, "providerFingerprint");
        DatasetVersion.requireText(model, "model");
        if (trialNo < 0) {
            throw new IllegalArgumentException("trialNo 不得为负");
        }
    }

    /** INV-AM5-3：两态四字段全记录 + 身份齐全 + 已配对（trial ≥ 1）才可进正式门禁 */
    public boolean isGateEligible() {
        return isComplete(requested) && isComplete(effective)
                && trialNo >= 1;
    }

    private static boolean isComplete(Sampling s) {
        return s.temperature() != null && s.topP() != null
                && s.maxTokens() != null && s.seed() != null;
    }

    /** 固定字段序规范串（digest 输入；改动本格式即改指纹语义，测试钉值会红） */
    public String canonical() {
        return "model=" + model
                + "|provider=" + providerFingerprint
                + "|trial=" + trialNo
                + "|requested=" + canonical(requested)
                + "|effective=" + canonical(effective);
    }

    private static String canonical(Sampling s) {
        return "{temperature=" + s.temperature()
                + ",top_p=" + s.topP()
                + ",max_tokens=" + s.maxTokens()
                + ",seed=" + s.seed() + "}";
    }

    /** 指纹内容摘要（BA-22：钉值断言必须实测校准——SamplingFingerprintTest 对拍） */
    public Digest digest() {
        return Digest.sha256Of(canonical());
    }

    /** V23 jsonb 落库形态：顶层键序 = ck 键集序；内层键序 = canonical 四字段序 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requested", samplingMap(requested));
        map.put("effective", samplingMap(effective));
        map.put("provider_fingerprint", providerFingerprint);
        map.put("model", model);
        map.put("trial_no", trialNo);
        return map;
    }

    private static Map<String, Object> samplingMap(Sampling s) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("temperature", s.temperature());
        inner.put("top_p", s.topP());
        inner.put("max_tokens", s.maxTokens());
        inner.put("seed", s.seed());
        return inner;
    }
}
