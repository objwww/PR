package com.objwww.pr.control.alert.infrastructure.catalog;

import com.objwww.pr.control.alert.domain.agent.RootCauseCatalogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 评测同义词词表 → 主 Agent 根因码表（告警-Agent 根因评分贯通修复，B 面）：
 * 与评测侧 {@code SynonymLexicon} 加载<b>同一份</b> synonym-lexicon 文件
 * （配置键 {@code app.alert.r7.primary.root-cause-catalog-path}，缺省与评测
 * {@code app.alert.eval.lexicon-path} 同默认值），把 canonical 码按场景装订成
 * {@link Entry} 清单供信封 {@code root_cause_catalog} 下发——synonyms 不下发
 * （评分侧容差，模型输出 canonical 码即命中）。
 *
 * <p>装订规则（确定性，零猜测）：
 * <ul>
 *   <li>每 reason_codes 条目一行：faultType=条目 fault_type 属主、scenario=
 *       bound_scenario、description=条目 description（机制级命名说明）；</li>
 *   <li>component=components 条目 bound_scenarios 包含该 scenario 的 code；
 *       无组件绑定该 scenario（如词表 v2 只增不改纪律下 S16~S25 的组件绑定
 *       仅以注释标注）→ WARN 留痕并跳过该行（诚实不完整，不臆造组件码）；</li>
 *   <li>条目缺 code/bound_scenario 等关键字段 → 解析失败（fail-fast 由
 *       {@link #parse} 抛出；装配面经 {@link #loadOrEmpty} 兜底为空表）。</li>
 * </ul>
 *
 * <p>可用性纪律：文件缺失/解析失败 → WARN + {@link RootCauseCatalogPort#EMPTY}
 * （信封不放 root_cause_catalog 键），<b>不 fail-fast 阻断告警主链</b>——
 * 码表是提示面，缺失只影响 root_cause_hit 评分上限，不影响告警调查本身。
 */
public final class YamlRootCauseCatalog implements RootCauseCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(YamlRootCauseCatalog.class);

    private final List<Entry> entries;

    private YamlRootCauseCatalog(List<Entry> entries) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    @Override
    public List<Entry> entries() {
        return entries;
    }

    /**
     * 装配面加载入口（告警可用性优先）：资源不存在/读取失败/解析失败一律
     * WARN + 空表，不抛错阻断主链。
     */
    public static RootCauseCatalogPort loadOrEmpty(ResourceLoader loader, String path) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(path, "path");
        try {
            Resource resource = loader.getResource(path);
            if (!resource.exists()) {
                log.warn("根因码表词表不存在，按空码表降级（信封省略 root_cause_catalog）"
                        + " path={}", path);
                return RootCauseCatalogPort.EMPTY;
            }
            try (Reader reader = new InputStreamReader(resource.getInputStream(),
                    StandardCharsets.UTF_8)) {
                RootCauseCatalogPort catalog = parse(reader);
                log.info("根因码表加载完成 path={} entries={}", path,
                        catalog.entries().size());
                return catalog;
            }
        } catch (Exception e) {
            log.warn("根因码表词表加载失败，按空码表降级（信封省略 root_cause_catalog）"
                    + " path={} cause={}", path, e.getMessage());
            return RootCauseCatalogPort.EMPTY;
        }
    }

    /**
     * 词表 YAML → 码表（结构非法抛 IllegalArgumentException——装配面由
     * {@link #loadOrEmpty} 兜底，直接调用方需自决可用性策略）。
     */
    @SuppressWarnings("unchecked")
    public static YamlRootCauseCatalog parse(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("词典根必须是映射");
        }
        // scenario → component（components.bound_scenarios 多对一装订）
        Map<String, String> scenarioComponent = new LinkedHashMap<>();
        Object components = map.get("components");
        if (!(components instanceof List<?> componentList)) {
            throw new IllegalArgumentException("词典 components 缺失或不是列表");
        }
        for (Object item : componentList) {
            Map<String, Object> e = (Map<String, Object>) item;
            String code = textOf(e, "code");
            Object bound = e.get("bound_scenarios");
            if (!(bound instanceof List<?> scenarios)) {
                continue;   // 无场景绑定的组件不参与装订（词表只增不改纪律下的注释绑定面）
            }
            for (Object scenario : scenarios) {
                scenarioComponent.putIfAbsent(Objects.toString(scenario, ""), code);
            }
        }
        Object reasonCodes = map.get("reason_codes");
        if (!(reasonCodes instanceof List<?> reasonList)) {
            throw new IllegalArgumentException("词典 reason_codes 缺失或不是列表");
        }
        List<Entry> out = new ArrayList<>();
        for (Object item : reasonList) {
            Map<String, Object> e = (Map<String, Object>) item;
            String reasonCode = textOf(e, "code");
            String faultType = textOf(e, "fault_type");
            String scenario = Objects.toString(e.get("bound_scenario"), "");
            String component = scenarioComponent.get(scenario);
            if (component == null) {
                log.warn("根因码表条目无组件绑定，跳过（诚实不完整）scenario={} "
                        + "reason_code={}", scenario, reasonCode);
                continue;
            }
            out.add(new Entry(component, faultType, reasonCode,
                    Objects.toString(e.get("description"), "")));
        }
        return new YamlRootCauseCatalog(out);
    }

    private static String textOf(Map<String, Object> entry, String field) {
        String value = Objects.toString(entry.get(field), null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("词典条目缺 " + field);
        }
        return value;
    }
}
