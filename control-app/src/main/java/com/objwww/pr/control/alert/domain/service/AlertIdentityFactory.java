package com.objwww.pr.control.alert.domain.service;

import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 纯函数：labels → incidentKey / payloadHash / investigationHash（§6.3 双哈希分离；不碰 DB）。
 *
 * <p>三身份的稳定标签纪律（INV-AM1-4）：
 * <ul>
 *   <li>incidentKey = 配置的 key 标签（默认 alertname/service/service_name/namespace/job），
 *       <b>不含告警级别</b>——升级不换单；标签顺序无关。</li>
 *   <li>payloadHash = sha256(规范化(全部 labels + status + startsAt))——判"是否处理过同一条"。</li>
 *   <li>investigationHash = sha256(key 标签 + 静态 annotations)——判"材料是否变化、值不值得重查"；
 *       排除动态数值 annotations（current_value 等），数值抖动不触发重查。</li>
 * </ul>
 */
public final class AlertIdentityFactory {

    /** incidentKey 参与标签（顺序即输出顺序；告警级别永不参与） */
    public static final List<String> DEFAULT_KEY_LABELS =
            List.of("alertname", "service", "service_name", "namespace", "job");

    /** 动态数值 annotations 默认排除集（investigationHash 不吃抖动） */
    public static final List<String> DEFAULT_DYNAMIC_ANNOTATIONS =
            List.of("current_value", "value", "observation_value");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_INSTANT;

    private final List<String> keyLabels;
    private final List<String> dynamicAnnotationKeys;

    public AlertIdentityFactory() {
        this(DEFAULT_KEY_LABELS, DEFAULT_DYNAMIC_ANNOTATIONS);
    }

    public AlertIdentityFactory(List<String> keyLabels, List<String> dynamicAnnotationKeys) {
        this.keyLabels = List.copyOf(keyLabels);
        this.dynamicAnnotationKeys = List.copyOf(dynamicAnnotationKeys);
    }

    /** 聚合身份：key 标签按配置序 k=v 用 '|' 连接；缺 alertname 直接拒绝（无法聚合） */
    public String incidentKey(Map<String, String> labels) {
        if (!labels.containsKey("alertname")) {
            throw new IllegalArgumentException("缺少 alertname 标签，无法铸造 incidentKey");
        }
        StringBuilder sb = new StringBuilder();
        for (String name : keyLabels) {
            String value = labels.get(name);
            if (value != null) {
                if (!sb.isEmpty()) {
                    sb.append('|');
                }
                sb.append(name).append('=').append(value);
            }
        }
        return sb.toString();
    }

    /** 判"同一条"：全部 labels（含级别）+ status + startsAt 的规范化摘要 */
    public Digest payloadHash(AlertFiringStatus status, Map<String, String> labels, Instant startsAt) {
        String canonical = "v1|" + status.raw() + "|" + canonicalLabels(labels)
                + "|" + ISO.format(startsAt);
        return Digest.sha256Of(canonical);
    }

    /** 判"值得重查"：key 标签 + 静态 annotations（排除动态数值键） */
    public Digest investigationHash(Map<String, String> labels, Map<String, String> annotations) {
        Map<String, String> keySubset = new LinkedHashMap<>();
        for (String name : keyLabels) {
            String value = labels.get(name);
            if (value != null) {
                keySubset.put(name, value);
            }
        }
        Map<String, String> staticAnnotations = new TreeMap<>(String::compareTo);
        for (Map.Entry<String, String> e : annotations.entrySet()) {
            if (!dynamicAnnotationKeys.contains(e.getKey())) {
                staticAnnotations.put(e.getKey(), e.getValue());
            }
        }
        return Digest.sha256Of("v1|" + canonicalLabels(keySubset)
                + "|" + canonicalLabels(staticAnnotations));
    }

    /** 标签按名字典序的 "k=v;..." 规范形（顺序无关的稳定序列化） */
    private static String canonicalLabels(Map<String, String> labels) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(labels).entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(";", parts);
    }
}
