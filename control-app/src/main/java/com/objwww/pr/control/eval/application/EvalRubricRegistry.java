package com.objwww.pr.control.eval.application;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * EV-08 评审 rubric 注册表装载器（eval-rubrics.yml → 域对象；DrillTemplateCatalog
 * 同律：只读白名单字段，缺必填即抛）。
 *
 * <p>冻结语义（§3.6"评分项使用冻结 rubric"）：评审提交必带 rubricVersion，服务端
 * 只接受注册表已知版本（未知版本 400——不自造版本锚）；rubric 变更 = 注册表新增
 * 一行新版本，旧版本行永不改写，已落档 review_verdict.rubric_version 永远可解析。
 * {@link #currentVersion()} 随数据集读面透出（EV-08 卡"rubric 版本随数据集版本透出"）。
 */
public final class EvalRubricRegistry {

    /** 一条评分项（冻结 rubric 的组成） */
    public record RubricItem(String id, String label, boolean required) {
    }

    /** 一个冻结 rubric 版本 */
    public record Rubric(String id, String version, boolean current, List<RubricItem> items) {
    }

    private final int registryVersion;
    private final List<Rubric> rubrics;

    private EvalRubricRegistry(int registryVersion, List<Rubric> rubrics) {
        this.registryVersion = registryVersion;
        this.rubrics = List.copyOf(rubrics);
    }

    public static EvalRubricRegistry load(Reader reader) {
        Object root = new Yaml().load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("rubric 注册表根必须是映射");
        }
        Object version = map.get("registry_version");
        if (!(version instanceof Number n)) {
            throw new IllegalArgumentException("缺少整型字段 registry_version");
        }
        Object raw = map.get("rubrics");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("缺少非空 rubrics 列表");
        }
        List<Rubric> rubrics = new ArrayList<>(list.size());
        for (Object row : list) {
            rubrics.add(rubric(cast(row)));
        }
        if (rubrics.stream().filter(Rubric::current).count() != 1) {
            throw new IllegalArgumentException("rubrics 必须恰一条 current=true");
        }
        long distinctVersions = rubrics.stream().map(Rubric::version).distinct().count();
        if (distinctVersions != rubrics.size()) {
            throw new IllegalArgumentException("rubric version 重复——冻结版本锚必须唯一");
        }
        return new EvalRubricRegistry(n.intValue(), rubrics);
    }

    public static EvalRubricRegistry load(String yaml) {
        return load(new StringReader(yaml));
    }

    public static EvalRubricRegistry load(InputStream stream) {
        return load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    public int registryVersion() {
        return registryVersion;
    }

    public List<Rubric> rubrics() {
        return rubrics;
    }

    /** 当前生效 rubric 版本（数据集读面透出 + 前端默认评分细则） */
    public String currentVersion() {
        return rubrics.stream().filter(Rubric::current).findFirst()
                .orElseThrow().version();
    }

    /** 已知版本全表（数据集详情透出；变更历史 = 注册表行序） */
    public List<String> knownVersions() {
        return rubrics.stream().map(Rubric::version).toList();
    }

    /** 版本锚解析（提交校验：未知版本 = 400，不自造） */
    public Optional<Rubric> find(String version) {
        return rubrics.stream().filter(r -> r.version().equals(version)).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("rubric 行必须是映射");
        }
        return (Map<String, Object>) m;
    }

    private static Rubric rubric(Map<String, Object> row) {
        String id = str(row, "id");
        String version = str(row, "version");
        boolean current = Boolean.TRUE.equals(row.get("current"));
        Object rawItems = row.get("items");
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("rubric " + version + " 缺少非空 items 列表");
        }
        List<RubricItem> items = new ArrayList<>(list.size());
        for (Object raw : list) {
            Map<String, Object> item = cast(raw);
            items.add(new RubricItem(str(item, "id"), str(item, "label"),
                    Boolean.TRUE.equals(item.get("required"))));
        }
        return new Rubric(id, version, current, List.copyOf(items));
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        Objects.requireNonNull(value, "缺少字段 " + key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("字段 " + key + " 必为非空字符串");
        }
        return s;
    }
}
