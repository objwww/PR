package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.shared.Digest;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GoldenCase 注册表装载器（M3-10 GoldenCase 适配器）：eval-scenarios.yml → 域对象。
 *
 * <p>只读已知键（registry_version/schema_version/lexicon_binding/scenarios 的白名单字段），
 * 注释与扩展键忽略——文件"只增不改"纪律下旧装载器不因新字段失效。digest 以规范化
 * 场景行（id|三元组|症状码|时间参数）计算，作为 EvalRun 可复现元数据
 * （M3-14 dataset/scenario digest 的来源）。
 *
 * <p>解析失败（缺 scenario_id/期望根因三元组/时间参数缺失）即抛 IllegalArgumentException——
 * 期望面残缺的注册表禁止进入评测。
 */
public final class GoldenScenarioRegistry {

    private final int registryVersion;
    private final int schemaVersion;
    private final String lexiconBinding;
    private final List<GoldenCase> scenarios;

    private GoldenScenarioRegistry(int registryVersion, int schemaVersion,
                                   String lexiconBinding, List<GoldenCase> scenarios) {
        this.registryVersion = registryVersion;
        this.schemaVersion = schemaVersion;
        this.lexiconBinding = lexiconBinding == null ? "" : lexiconBinding;
        this.scenarios = List.copyOf(scenarios);
    }

    public static GoldenScenarioRegistry load(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("注册表根必须是映射");
        }
        int registryVersion = intField(map, "registry_version");
        int schemaVersion = intField(map, "schema_version");
        List<GoldenCase> cases = ((List<?>) map.get("scenarios")).stream()
                .map(s -> goldenCase((Map<?, ?>) s))
                .toList();
        Object lexicon = map.get("lexicon_binding");
        return new GoldenScenarioRegistry(registryVersion, schemaVersion,
                lexicon == null ? null : lexicon.toString(), cases);
    }

    public static GoldenScenarioRegistry load(String yaml) {
        return load(new StringReader(yaml));
    }

    public static GoldenScenarioRegistry load(InputStream stream) {
        return load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static GoldenCase goldenCase(Map<?, ?> raw) {
        Map<String, Object> s = (Map<String, Object>) raw;
        String scenarioId = str(s, "scenario_id");
        Map<String, Object> rc = (Map<String, Object>) s.get("expected_root_cause");
        if (rc == null) {
            throw new IllegalArgumentException("场景 " + scenarioId + " 缺 expected_root_cause");
        }
        TypedRootCause rootCause = new TypedRootCause(
                str(rc, "component"), str(rc, "fault_type"), str(rc, "reason_code"));
        List<String> symptoms = s.get("expected_symptom_codes") == null ? List.of()
                : ((List<Object>) s.get("expected_symptom_codes")).stream()
                        .map(Object::toString).toList();
        Map<String, Object> t = (Map<String, Object>) s.get("timing");
        if (t == null) {
            throw new IllegalArgumentException("场景 " + scenarioId + " 缺 timing");
        }
        GoldenCase.Timing timing = new GoldenCase.Timing(
                intField(t, "preheat_seconds"), intField(t, "hold_seconds"),
                intField(t, "max_firing_wait_seconds"),
                intField(t, "max_resolved_wait_seconds"),
                intField(t, "cleanup_timeout_seconds"));
        return new GoldenCase(scenarioId, strOrNull(s, "name"), strOrNull(s, "driver"),
                strOrNull(s, "chaos_family"), strOrNull(s, "target"),
                rootCause, symptoms, timing);
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

    private static int intField(Map<?, ?> map, Object key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("缺少整型字段 " + key);
        }
        return n.intValue();
    }

    public int registryVersion() {
        return registryVersion;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String lexiconBinding() {
        return lexiconBinding;
    }

    public List<GoldenCase> scenarios() {
        return scenarios;
    }

    public GoldenCase byScenarioId(String scenarioId) {
        return scenarios.stream()
                .filter(c -> c.scenarioId().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("场景未注册: " + scenarioId));
    }

    /** 注册表内容摘要（canonical 行序化；EvalRun 可复现元数据的 dataset digest 来源） */
    public Digest contentDigest() {
        StringBuilder canonical = new StringBuilder("registry=").append(registryVersion)
                .append(";schema=").append(schemaVersion)
                .append(";lexicon=").append(lexiconBinding);
        for (GoldenCase c : scenarios) {
            canonical.append("|case=").append(c.scenarioId())
                    .append(',').append(c.expectedRootCause().component())
                    .append(',').append(c.expectedRootCause().faultType())
                    .append(',').append(c.expectedRootCause().reasonCode())
                    .append(',').append(String.join("+", c.expectedSymptomCodes()));
        }
        return Digest.sha256Of(canonical.toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GoldenScenarioRegistry other
                && registryVersion == other.registryVersion
                && schemaVersion == other.schemaVersion
                && scenarios.equals(other.scenarios);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registryVersion, schemaVersion, scenarios);
    }
}
