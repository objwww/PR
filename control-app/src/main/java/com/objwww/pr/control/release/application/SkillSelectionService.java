package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import com.objwww.pr.control.release.domain.service.SkillMatcher;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Skill 生产选择面（EN-08 → R7 装配缝，§四"有效权限交集/首期每角色≤1"）：
 * 只选 ACTIVE 候选（S08 生产/评测身份隔离），selector 双维命中（S06），冲突字典序
 * 取一（S07），<b>run 级钉版</b>（S11：v1 Run 运行中发布 v2 不切换——首次选择
 * （含"无匹配"）即随 Run 固定，新 Run 才按新集合选择）。
 *
 * <p>钉版实现 = 进程内 LRU（{@value #PIN_CAPACITY} run 上界；重启后按当时集合重选
 * = 新装配时点，偏差登记）。无匹配同样钉空：发布不追溯影响已运行 Run。
 * 只读面：本服务零写入。
 */
public class SkillSelectionService {

    private static final Logger log = LoggerFactory.getLogger(SkillSelectionService.class);

    /** run 钉版容量（LRU 上界，防长生命周期进程无界增长） */
    static final int PIN_CAPACITY = 512;

    /** 选中 Skill 的受控视图（信封下发形状；none = 钉空） */
    public record SkillView(String name, String assetDigest, String body,
            List<String> steps, List<String> tools, boolean conflictSuppressed) {

        public static SkillView none() {
            return new SkillView(null, null, null, List.of(), List.of(), false);
        }

        public boolean present() {
            return name != null;
        }
    }

    private final SkillCandidateRepository candidates;
    private final ReleaseAssetRepository assets;
    private final Map<UUID, SkillView> pin =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(PIN_CAPACITY, 0.75f,
                    false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, SkillView> eldest) {
                    return size() > PIN_CAPACITY;
                }
            });

    public SkillSelectionService(SkillCandidateRepository candidates,
            ReleaseAssetRepository assets) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    /**
     * 按 run 钉版选择：首次调用选定（或钉空），此后同 run 恒返回同视图。
     * @return none = 无匹配（钉空，退回通用调查）
     */
    public SkillView select(UUID runId, String alertname, String service) {
        synchronized (pin) {
            SkillView pinned = pin.get(runId);
            if (pinned != null || pin.containsKey(runId)) {
                return pinned;
            }
        }
        SkillView selected = selectFresh(alertname, service);
        synchronized (pin) {
            pin.put(runId, selected);
        }
        if (selected.present()) {
            log.info("Skill 钉版（S11）：run={} name={} digest={}（conflictSuppressed={}）",
                    runId, selected.name(), selected.assetDigest().substring(0, 8),
                    selected.conflictSuppressed());
        }
        return selected;
    }

    private SkillView selectFresh(String alertname, String service) {
        List<Map<String, Object>> active = new ArrayList<>();
        for (SkillCandidate candidate : candidates.listByStatus(SkillCandidate.ST_ACTIVE)) {
            if (candidate.assetDigest() == null) {
                continue;
            }
            Optional<ReleaseAsset> asset = assets.findByDigest(ReleaseAsset.KIND_SKILL,
                    new Digest(candidate.assetDigest()));
            asset.ifPresent(a -> active.add(Map.of(
                    "name", candidate.name(),
                    "asset_digest", candidate.assetDigest(),
                    "manifest", a.content().getOrDefault("manifest", Map.of()))));
        }
        SkillMatcher.Match match = SkillMatcher.match(alertname, service, active);
        if (match == null) {
            return SkillView.none();
        }
        return active.stream()
                .filter(m -> match.name().equals(m.get("name")))
                .findFirst()
                .map(m -> viewOf(match.name(), (String) m.get("asset_digest"),
                        match.conflictSuppressed()))
                .orElse(SkillView.none());
    }

    /** 资产 → 受控视图（body/manifest 步骤与工具；资产缺席=诚实 none，不造占位） */
    private SkillView viewOf(String name, String assetDigest, boolean conflictSuppressed) {
        Optional<ReleaseAsset> asset = assets.findByDigest(ReleaseAsset.KIND_SKILL,
                new Digest(assetDigest));
        if (asset.isEmpty() || !(asset.get().content().get("manifest") instanceof Map<?, ?> m)) {
            log.warn("ACTIVE Skill 资产缺席或形状非法，按无匹配处理：{}", assetDigest);
            return SkillView.none();
        }
        String body = String.valueOf(asset.get().content().getOrDefault("body", ""));
        return new SkillView(name, assetDigest, body, stringsOf(m.get("steps")),
                stringsOf(m.get("tools")), conflictSuppressed);
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
