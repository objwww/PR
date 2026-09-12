package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.release.application.CanaryWindowTask;
import com.objwww.pr.control.release.domain.model.CanaryEvidenceClass;
import com.objwww.pr.control.release.domain.model.CanaryWindowPolicy;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.service.CanaryWindowEvaluator;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * bundle 派生的 canary 窗口身份/策略源（B4，infrastructure 粘合层——消费
 * ConfigBundleRepository 激活指针 + NativeCapabilityProbe 能力指纹）。
 *
 * <p>身份契约（V30 窗判定稳定三 digest）：candidateDigest = 激活 bundle digest
 * （行为候选身份）；rolloutPolicyDigest = canary 段 canonical sha256（O-63 版本化
 * 禁硬编码）；capabilityDigest = NativeCapabilityProbe 产物（not ready = 缺席 →
 * 窗口任务诚实空转，不落判定）。percent 带取 canary 段 from_percent/to_percent
 * （缺省 0/100，偏差登记：比例带调整即新身份由 candidate/policy digest 承载）。
 * rolloutId = bundle+capability 派生的确定性实例 id。
 */
public class BundleBackedCanarySources implements CanaryWindowTask.PolicySource,
        CanaryWindowTask.WindowIdentitySource {

    private final ConfigBundleRepository bundles;
    private final NativeCapabilityProbe probe;

    public BundleBackedCanarySources(ConfigBundleRepository bundles,
            NativeCapabilityProbe probe) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    public Optional<CanaryWindowPolicy> current() {
        return canarySection().flatMap(CanaryWindowPolicy::fromBundle);
    }

    @Override
    public Optional<CanaryWindowEvaluator.WindowIdentity> current(Instant windowStart,
            Instant windowEnd, int windowSeq) {
        Optional<ConfigBundleRepository.ActivePointer> pointer = bundles.findActivePointer();
        if (pointer.isEmpty()) {
            return Optional.empty();
        }
        String capabilityDigest;
        try {
            capabilityDigest = probe.capabilityDigest().hex();
        } catch (IllegalStateException e) {
            return Optional.empty(); // capability 不完整 = 诚实缺席，不落判定
        }
        Map<String, Object> section = canarySection().orElse(Map.of());
        String rolloutPolicyDigest = com.objwww.pr.shared.Digest
                .sha256Of(com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                        .canonicalize(section)).value();
        String bundleDigest = pointer.get().bundleDigest().hex();
        UUID rolloutId = UUID.nameUUIDFromBytes(
                (bundleDigest + ":" + capabilityDigest)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Optional.of(new CanaryWindowEvaluator.WindowIdentity(
                rolloutId, bundleDigest, rolloutPolicyDigest, capabilityDigest,
                intOf(section, "from_percent", 0), intOf(section, "to_percent", 100),
                windowSeq, CanaryEvidenceClass.LIVE_CANARY, windowStart, windowEnd));
    }

    /** bundle content 的 canary 段（缺激活指针/缺段 = empty，fail-closed） */
    private Optional<Map<String, Object>> canarySection() {
        return bundles.findActivePointer()
                .flatMap(p -> bundles.findByDigest(p.bundleDigest()))
                .map(bundle -> bundle.content().get("canary"))
                .filter(section -> section instanceof Map<?, ?>)
                .map(section -> (Map<String, Object>) section);
    }

    private static int intOf(Map<String, Object> section, String key, int fallback) {
        return section.get(key) instanceof Integer i ? i : fallback;
    }
}
