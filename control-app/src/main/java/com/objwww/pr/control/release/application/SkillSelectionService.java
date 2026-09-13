package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseManifest;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.model.SkillRunBinding;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import com.objwww.pr.control.release.domain.repository.SkillRunBindingRepository;
import com.objwww.pr.control.release.domain.service.SkillMatcher;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Skill 生产选择面（EN-08 → CL-05 持久绑定，告警-Agent闭环修复 v1 §4.2）：
 * 每 (run, role, configEpoch) 的选择是<b>持久事实</b>（V100 rca_run_skill_binding，
 * SELECTED 或明确 NONE）——重启不漂移、发布不追溯、并发双首次 insert-if-absent
 * 取一。原进程内 LRU 钉版（512 项）移除：事实源改为数据库行，开销先实测再加缓存。
 *
 * <p>选择材料全部来自服务端可信身份（冻结绑定 roleId/configEpoch/releaseDigest +
 * 装配材料 alertname/service）：新选择只读该 release_manifest.skills 冻结允许集
 * ∩ ACTIVE 候选（DEPRECATED 禁新选但不破坏老绑定；不用最新全局 ACTIVE 集合冒充
 * 冻结允许集）。选择器语义不变（S06 双维命中/S07 冲突字典序），版本钉
 * {@value #SELECTOR_VERSION}。
 *
 * <p>消费面（既有绑定读取）：RETIRED 紧急撤销阻断后续消费；资产/候选缺席显式
 * none + 告警（不静默换替补 Skill）；无组合身份（releaseDigest=null 的存量绑定）
 * 不回填不钉版，诚实 none。
 */
public class SkillSelectionService {

    private static final Logger log = LoggerFactory.getLogger(SkillSelectionService.class);

    /** 选择器算法版本（S06/S07 语义锚；重放解释随绑定行落档） */
    public static final String SELECTOR_VERSION = "skill-selector.v1";

    /** 选中 Skill 的受控视图（信封下发形状；none = 无匹配/钉空/消费阻断） */
    public record SkillView(String name, String assetDigest, String body,
            List<String> steps, List<String> tools, boolean conflictSuppressed) {

        public static SkillView none() {
            return new SkillView(null, null, null, List.of(), List.of(), false);
        }

        public boolean present() {
            return name != null;
        }
    }

    private final SkillRunBindingRepository bindings;
    private final SkillCandidateRepository candidates;
    private final ReleaseAssetRepository assets;
    private final ConfigBundleRepository bundles;
    private final Supplier<java.time.Instant> clock;

    public SkillSelectionService(SkillRunBindingRepository bindings,
            SkillCandidateRepository candidates, ReleaseAssetRepository assets,
            ConfigBundleRepository bundles, Supplier<java.time.Instant> clock) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 每 Run 钉版选择（装配动作每次经此口）：有持久绑定按 digest 装载；没有则按
     * 冻结允许集新选一次并 insert-if-absent 钉版（无匹配钉 NONE，发布不追溯）。
     * @return none = 无匹配/钉空/消费阻断
     */
    public SkillView selectPinned(UUID runId, String roleId, Long configEpoch,
            String releaseDigest, String alertname, String service) {
        if (releaseDigest == null) {
            // 无组合身份的存量 run：不能拿今天 ACTIVE 集回填"当时选择"（§4.3）
            return SkillView.none();
        }
        return viewOf(resolve(runId, roleId, configEpoch, releaseDigest,
                alertname, service, null));
    }

    /** CL-05 §4.3 热切同事务预生成：新代际的选择记录（或明确 NONE）落库即钉版 */
    public void provisionForEpoch(UUID runId, String roleId, long configEpoch,
            String releaseDigest, String alertname, String service,
            UUID sourceCommandId) {
        Objects.requireNonNull(releaseDigest, "releaseDigest");
        resolve(runId, roleId, configEpoch, releaseDigest, alertname, service,
                sourceCommandId);
    }

    // ------------------------------------------------------------------ 内部

    /** 绑定事实解析：既有行直读；缺失才新选并 insert-if-absent（并发败者读胜者） */
    private SkillRunBinding resolve(UUID runId, String roleId, Long configEpoch,
            String releaseDigest, String alertname, String service,
            UUID sourceCommandId) {
        long epochKey = configEpoch == null ? 0L : configEpoch;
        Optional<SkillRunBinding> existing = bindings.find(runId, roleId, epochKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String chosen = selectAllowed(releaseDigest, alertname, service);
        SkillRunBinding row = new SkillRunBinding(runId, roleId, epochKey,
                chosen != null ? SkillRunBinding.SELECTED : SkillRunBinding.NONE,
                chosen, releaseDigest, SELECTOR_VERSION, sourceCommandId, clock.get());
        if (!bindings.insertIfAbsent(row)) {
            // 并发胜者已在库：所有调用返回胜者事实
            return bindings.find(runId, roleId, epochKey).orElse(row);
        }
        log.info("Skill 绑定钉版（CL-05）：run={} role={} epoch={} status={} digest={} source={}",
                runId, roleId, epochKey, row.selectionStatus(),
                chosen == null ? "-" : chosen.substring(0, 8),
                sourceCommandId == null ? "assembly" : "config-switch");
        return row;
    }

    /** 冻结允许集内新选：ACTIVE ∩ release_manifest.skills，双维命中冲突字典序取一 */
    private String selectAllowed(String releaseDigest, String alertname, String service) {
        Set<String> allowed = allowedSkills(releaseDigest);
        if (allowed.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SkillCandidate candidate : candidates.listByStatus(SkillCandidate.ST_ACTIVE)) {
            if (candidate.assetDigest() == null
                    || !allowed.contains(candidate.assetDigest())) {
                continue;
            }
            assets.findByDigest(ReleaseAsset.KIND_SKILL,
                    new Digest(candidate.assetDigest())).ifPresent(a -> rows.add(Map.of(
                    "name", candidate.name(),
                    "asset_digest", candidate.assetDigest(),
                    "manifest", a.content().getOrDefault("manifest", Map.of()))));
        }
        SkillMatcher.Match match = SkillMatcher.match(alertname, service, rows);
        if (match == null) {
            return null;
        }
        return rows.stream()
                .filter(m -> match.name().equals(m.get("name")))
                .findFirst()
                .map(m -> (String) m.get("asset_digest"))
                .orElse(null);
    }

    /** 该 release 组合的 Skill 冻结允许集（无 manifest 段的存量 bundle = 空集） */
    private Set<String> allowedSkills(String releaseDigest) {
        return bundles.findByDigest(new Digest(releaseDigest))
                .flatMap(bundle -> ReleaseManifest.fromContent(bundle.content()))
                .map(ReleaseManifest::skills)
                .orElse(List.of())
                .stream().collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 绑定事实 → 消费视图：RETIRED/资产缺席显式阻断，不静默换替补 */
    private SkillView viewOf(SkillRunBinding binding) {
        if (!binding.selected()) {
            return SkillView.none();
        }
        SkillCandidate candidate =
                candidates.findByAssetDigest(binding.assetDigest()).orElse(null);
        if (candidate == null || SkillCandidate.ST_RETIRED.equals(candidate.status())) {
            log.warn("Skill 绑定消费阻断（候选缺席/RETIRED 紧急撤销）：run={} role={} digest={}",
                    binding.runId(), binding.roleId(), binding.assetDigest());
            return SkillView.none();
        }
        Optional<ReleaseAsset> asset = assets.findByDigest(ReleaseAsset.KIND_SKILL,
                new Digest(binding.assetDigest()));
        if (asset.isEmpty()
                || !(asset.get().content().get("manifest") instanceof Map<?, ?> m)) {
            log.warn("Skill 绑定资产缺席/形状非法（CAPABILITY_UNAVAILABLE，不换替补）：{}",
                    binding.assetDigest());
            return SkillView.none();
        }
        String body = String.valueOf(asset.get().content().getOrDefault("body", ""));
        return new SkillView(candidate.name(), binding.assetDigest(), body,
                stringsOf(m.get("steps")), stringsOf(m.get("tools")), false);
    }

    private static List<String> stringsOf(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /** 权限交集收口面（§四：有效权限=系统策略∩角色权限∩Skill声明）：Skill 声明 ∩ 角色 allowlist */
    public static List<String> intersectTools(SkillView view, Set<String> allowlist) {
        if (!view.present()) {
            return List.of();
        }
        return view.tools().stream().filter(allowlist::contains).toList();
    }
}
