package com.objwww.pr.control.eval.domain;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 评测同义词白名单（synonym-lexicon-v1.yml 装载器；root_cause_hit 维度的唯一判定面）。
 *
 * <p>冻结匹配语义（词典 M-01~M-06，评分器实现逐条一致）：
 * <ul>
 *   <li>M-02 匹配 = 规范化后与 {canonical 码} ∪ synonyms 的<b>全串精确等值</b>——
 *       禁止子串、正则、词干化、编辑距离等任何模糊匹配（INV-AM3-1 评分确定性）；</li>
 *   <li>M-03 规范化 = 去首尾空白 + ASCII casefold（中文原样）；</li>
 *   <li>M-04 白名单外 = NO_MATCH：记 miss、原值留失败样本清单，不抛错、不跨类猜测；</li>
 *   <li>M-06 本表只判命中——分子分母归三指标公式层（{@link ScenarioMetrics}）。</li>
 * </ul>
 *
 * <p>三维度（component/fault_type/reason_code）各自独立枚举；reason_code 与 fault_type
 * 的从属关系（一 fault_type 一码）在 {@link #reasonCodeMatches} 中一并校验——跨类归属
 * 的码（如 S1 的 reason_code 配 S2 的 fault_type）不算命中。
 */
public final class SynonymLexicon {

    private final int lexiconVersion;
    private final Map<String, List<String>> componentVariants = new LinkedHashMap<>();
    private final Map<String, List<String>> faultTypeVariants = new LinkedHashMap<>();
    private final Map<String, String> reasonCodeOwner = new LinkedHashMap<>();
    private final Map<String, List<String>> reasonCodeVariants = new LinkedHashMap<>();

    private SynonymLexicon(int lexiconVersion) {
        this.lexiconVersion = lexiconVersion;
    }

    public static SynonymLexicon load(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("词典根必须是映射");
        }
        Object version = map.get("lexicon_version");
        if (!(version instanceof Number n)) {
            throw new IllegalArgumentException("缺少整型字段 lexicon_version");
        }
        SynonymLexicon lexicon = new SynonymLexicon(n.intValue());
        lexicon.loadDimension(map.get("components"), "code", "synonyms",
                lexicon.componentVariants, null);
        lexicon.loadDimension(map.get("fault_types"), "code", "synonyms",
                lexicon.faultTypeVariants, null);
        loadReasonCodes(map.get("reason_codes"), lexicon);
        return lexicon;
    }

    public static SynonymLexicon load(String yaml) {
        return load(new StringReader(yaml));
    }

    @SuppressWarnings("unchecked")
    private void loadDimension(Object entries, String codeKey, String synonymKey,
                               Map<String, List<String>> sink, Map<String, String> ownerSink) {
        if (!(entries instanceof List<?> list)) {
            throw new IllegalArgumentException("词典维度缺失或不是列表");
        }
        for (Object entry : list) {
            Map<String, Object> e = (Map<String, Object>) entry;
            String code = Objects.toString(e.get(codeKey), null);
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("词典条目缺 " + codeKey);
            }
            List<String> variants = e.get(synonymKey) == null ? List.of()
                    : ((List<Object>) e.get(synonymKey)).stream().map(Object::toString).toList();
            sink.put(code, variants);
            if (ownerSink != null) {
                ownerSink.put(code, Objects.toString(e.get("fault_type"), ""));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadReasonCodes(Object entries, SynonymLexicon lexicon) {
        if (!(entries instanceof List<?> list)) {
            throw new IllegalArgumentException("词典 reason_codes 缺失或不是列表");
        }
        for (Object entry : list) {
            Map<String, Object> e = (Map<String, Object>) entry;
            String code = Objects.toString(e.get("code"), null);
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("词典条目缺 code");
            }
            String owner = Objects.toString(e.get("fault_type"), "");
            lexicon.reasonCodeOwner.put(code, owner);
            List<String> variants = e.get("synonyms") == null ? List.of()
                    : ((List<Object>) e.get("synonyms")).stream().map(Object::toString).toList();
            lexicon.reasonCodeVariants.put(code, variants);
        }
    }

    public int lexiconVersion() {
        return lexiconVersion;
    }

    /** M-02/03/04：全串精确等值（规范化后 ∈ {canonical} ∪ synonyms）；白名单外 = false */
    public boolean componentMatches(String expectedCode, String actual) {
        return matches(componentVariants, expectedCode, actual);
    }

    public boolean faultTypeMatches(String expectedCode, String actual) {
        return matches(faultTypeVariants, expectedCode, actual);
    }

    /** reason_code 命中额外要求从属 fault_type 一致（跨场景配对不算命中，M-05 场景绑定面） */
    public boolean reasonCodeMatches(String expectedCode, String expectedFaultType, String actual) {
        if (!matches(reasonCodeVariants, expectedCode, actual)) {
            return false;
        }
        return expectedFaultType.equals(reasonCodeOwner.get(expectedCode));
    }

    private static boolean matches(Map<String, List<String>> variants, String expectedCode,
                                   String actual) {
        if (actual == null || actual.isBlank() || !variants.containsKey(expectedCode)) {
            return false;
        }
        String candidate = normalize(actual);
        if (normalize(expectedCode).equals(candidate)) {
            return true;
        }
        return variants.get(expectedCode).stream()
                .map(SynonymLexicon::normalize)
                .anyMatch(candidate::equals);
    }

    /** M-03：去首尾空白 + ASCII casefold（Locale.ROOT 只折叠 ASCII；中文原样） */
    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
