package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.shared.Digest;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * DR-02 演练模板目录装载器（drill-templates.yml → 域对象；GoldenScenarioRegistry
 * 同律：只读白名单字段，注释与扩展键忽略，缺必填即抛）。
 *
 * <p>本目录是发布展示 DTO 面——只含公开字段；expected_root_cause 等 GT 键即使出现
 * 在文件里也不会被读入域对象（白名单装载天然剥离）。
 *
 * <p>{@link #contentDigest()} = canonical 模板行摘要：作业受理时冻结为
 * drill_job.template_digest（§7.4"场景模板及注入参数在启动时冻结"）。
 */
public final class DrillTemplateCatalog {

    private final int registryVersion;
    private final List<DrillTemplate> templates;

    private DrillTemplateCatalog(int registryVersion, List<DrillTemplate> templates) {
        this.registryVersion = registryVersion;
        this.templates = List.copyOf(templates);
    }

    public static DrillTemplateCatalog load(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("模板目录根必须是映射");
        }
        Object version = map.get("registry_version");
        if (!(version instanceof Number n)) {
            throw new IllegalArgumentException("缺少整型字段 registry_version");
        }
        Object raw = map.get("templates");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("缺少 templates 列表");
        }
        return new DrillTemplateCatalog(n.intValue(),
                list.stream().map(t -> template(cast(t))).toList());
    }

    public static DrillTemplateCatalog load(String yaml) {
        return load(new StringReader(yaml));
    }

    public static DrillTemplateCatalog load(InputStream stream) {
        return load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("模板行必须是映射");
        }
        return (Map<String, Object>) m;
    }

    private static DrillTemplate template(Map<String, Object> s) {
        String scenarioId = str(s, "scenario_id");
        Map<String, Object> t = cast(s.get("timing"));
        DrillTemplate.Timing timing = new DrillTemplate.Timing(
                intField(t, "preheat_seconds"), intField(t, "hold_seconds"),
                intField(t, "max_firing_wait_seconds"),
                intField(t, "max_resolved_wait_seconds"),
                intField(t, "cleanup_timeout_seconds"));
        Map<String, Object> p = cast(s.get("params"));
        Map<String, Object> duration = cast(p.get("duration_seconds"));
        Object scales = p.get("traffic_scales");
        List<String> trafficScales = scales instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : List.of();
        DrillTemplate.ParamWhitelist params = new DrillTemplate.ParamWhitelist(
                intField(duration, "default"), intField(duration, "min"),
                intField(duration, "max"), trafficScales,
                Boolean.TRUE.equals(p.get("linked_eval_version_allowed")));
        Map<String, Object> e = cast(s.get("execution"));
        DrillTemplate.Execution execution = new DrillTemplate.Execution(
                Boolean.TRUE.equals(e.get("ready")), strOrNull(e, "reason"));
        Object symptoms = s.get("symptom_codes");
        List<String> symptomCodes = symptoms instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : List.of();
        return new DrillTemplate(scenarioId, str(s, "name"), strOrNull(s, "scenario_type"),
                strOrNull(s, "fault_source"), strOrNull(s, "driver"),
                strOrNull(s, "chaos_family"), strOrNull(s, "target"), symptomCodes,
                strOrNull(s, "symptom_display"), strOrNull(s, "impact"),
                timing, params, execution);
    }

    private static String str(Map<String, Object> map, String key) {
        String value = strOrNull(map, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段 " + key);
        }
        return value;
    }

    private static String strOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static int intField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("缺少整型字段 " + key);
        }
        return n.intValue();
    }

    public int registryVersion() {
        return registryVersion;
    }

    public List<DrillTemplate> templates() {
        return templates;
    }

    public Optional<DrillTemplate> byScenarioId(String scenarioId) {
        return templates.stream().filter(t -> t.scenarioId().equals(scenarioId)).findFirst();
    }

    /** 目录内容摘要（canonical 行序化；作业 template_digest 冻结源） */
    public Digest contentDigest() {
        StringBuilder canonical = new StringBuilder("drill-templates/v1|registry=")
                .append(registryVersion);
        for (DrillTemplate t : templates) {
            canonical.append("|template=").append(t.scenarioId())
                    .append(',').append(t.name())
                    .append(',').append(t.driver())
                    .append(',').append(t.chaosFamily())
                    .append(',').append(String.join("+", t.symptomCodes()))
                    .append(',').append(t.timing().preheatSeconds())
                    .append(',').append(t.timing().holdSeconds())
                    .append(',').append(t.timing().maxFiringWaitSeconds())
                    .append(',').append(t.timing().maxResolvedWaitSeconds())
                    .append(',').append(t.timing().cleanupTimeoutSeconds())
                    .append(',').append(t.params().durationDefaultSeconds())
                    .append(',').append(t.params().durationMinSeconds())
                    .append(',').append(t.params().durationMaxSeconds())
                    .append(',').append(String.join("+", t.params().trafficScales()))
                    .append(',').append(t.execution().ready());
        }
        return Digest.sha256Of(canonical.toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DrillTemplateCatalog other
                && registryVersion == other.registryVersion
                && templates.equals(other.templates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registryVersion, templates);
    }
}
