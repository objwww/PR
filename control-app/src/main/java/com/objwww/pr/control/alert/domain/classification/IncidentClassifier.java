package com.objwww.pr.control.alert.domain.classification;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * UX-01 规则分类器：纯函数、确定性、规则优先（首期不接 LLM——方案 §六 /
 * 告警-UX迭代-分类与值班机器人方案-v1.md §一）。
 *
 * <p>输入只取可信标签面：alertname、domain/category（直标签）、service/service_name。
 * <b>severity 不参与分类</b>（分类与严重度独立，INV：升级不换类）。
 *
 * <p>裁决律：
 * <ul>
 *   <li>{@link #RULES} 表序即冻结优先级，首条命中即裁决；ruleId 稳定不重排——
 *       多命中时优先级与 ruleId 就是全部依据，没有权重分值；</li>
 *   <li>PLATFORM 置顶：控制面自身异常绝不落入 7 个业务类；SECURITY 次之
 *       （越权/泄露语义优先于宿主面归属）；APPLICATION 垫底（最泛兜底类）；</li>
 *   <li>零命中 → UNCLASSIFIED（ruleId=FALLBACK-UNCLASSIFIED），不给假概率；</li>
 *   <li>匹配面有界：输入先截 512 字符再归一化（小写、剔除非 [a-z0-9]），
 *       只做子串/等值匹配，无正则——复杂度天然有界（方案"正则限定复杂度和输入长度"）。</li>
 * </ul>
 */
public final class IncidentClassifier {

    /** 规则表版本（Git 审查演进；落 incident.category_rule_version，重分类可追溯） */
    public static final String RULE_VERSION = "ux01-rules-v1";

    public static final String FALLBACK_RULE_ID = "FALLBACK-UNCLASSIFIED";

    /** 单标签输入上限（字符）——超长标签截断后匹配，防失控输入放大匹配成本 */
    static final int MAX_INPUT_CHARS = 512;

    private enum Target {
        /** alertname 归一化后子串命中 */
        ALERTNAME_CONTAINS,
        /** domain/category 直标签归一化等值 */
        DOMAIN_LABEL,
        /** service/service_name 归一化等值 */
        SERVICE_LABEL
    }

    private record Rule(String ruleId, IncidentCategory category, Target target,
                        List<String> needles, String basisTemplate) {
    }

    /** 控制面服务集合（归一化等值；控制面自身异常 = PLATFORM，不入业务类） */
    private static final List<String> CONTROL_PLANE_SERVICES =
            List.of("controlapp", "notifyapp", "dutyadapter");

    /**
     * 冻结规则表（表序 = 优先级；改表 = 改 RULE_VERSION + Git 审查）。
     * 每类至少两条规则（直标签 + alertname 关键词），PLATFORM 双规则置顶。
     */
    private static final List<Rule> RULES = List.of(
            // ---- PLATFORM（独立出口，置顶） ----
            new Rule("PLATFORM-SERVICE", IncidentCategory.PLATFORM, Target.SERVICE_LABEL,
                    CONTROL_PLANE_SERVICES, "service 属控制面集合"),
            new Rule("PLATFORM-ALERTNAME", IncidentCategory.PLATFORM, Target.ALERTNAME_CONTAINS,
                    List.of("controlapp", "notifyapp", "dutyadapter", "controlplane"),
                    "alertname 指向控制面组件"),
            // ---- SECURITY（越权/泄露语义优先于宿主归属） ----
            new Rule("SECURITY-LABEL", IncidentCategory.SECURITY, Target.DOMAIN_LABEL,
                    List.of("security"), "label domain/category=security"),
            new Rule("SECURITY-ALERTNAME", IncidentCategory.SECURITY, Target.ALERTNAME_CONTAINS,
                    List.of("authfail", "unauthorized", "forbidden", "bruteforce",
                            "credentialleak", "policyviolation", "intrusion"),
                    "alertname 含安全语义关键词"),
            // ---- 7 个业务类（按特异性降序） ----
            new Rule("BUSINESS-LABEL", IncidentCategory.BUSINESS, Target.DOMAIN_LABEL,
                    List.of("business"), "label domain/category=business"),
            new Rule("BUSINESS-ALERTNAME", IncidentCategory.BUSINESS, Target.ALERTNAME_CONTAINS,
                    // "slo" 裸词会误中 "dnslookup" 等子串——只用可辨识复合词
                    List.of("orderfail", "paymentfail", "checkoutfail", "sloburn",
                            "conversion", "businessloss"),
                    "alertname 含业务指标关键词"),
            new Rule("DATA-LABEL", IncidentCategory.DATA, Target.DOMAIN_LABEL,
                    List.of("data"), "label domain/category=data"),
            new Rule("DATA-ALERTNAME", IncidentCategory.DATA, Target.ALERTNAME_CONTAINS,
                    List.of("dataquality", "datafreshness", "datastaleness",
                            "pipelinefail", "etlfail"),
                    "alertname 含数据质量/流水线关键词"),
            new Rule("NETWORK-LABEL", IncidentCategory.NETWORK, Target.DOMAIN_LABEL,
                    List.of("network"), "label domain/category=network"),
            new Rule("NETWORK-ALERTNAME", IncidentCategory.NETWORK, Target.ALERTNAME_CONTAINS,
                    List.of("dns", "tls", "ssl", "packetloss", "networkunreachable",
                            "connectionreset", "tcpretransmit"),
                    "alertname 含网络关键词"),
            new Rule("DEPENDENCY-LABEL", IncidentCategory.DEPENDENCY, Target.DOMAIN_LABEL,
                    List.of("dependency"), "label domain/category=dependency"),
            new Rule("DEPENDENCY-ALERTNAME", IncidentCategory.DEPENDENCY, Target.ALERTNAME_CONTAINS,
                    List.of("postgres", "mysql", "redis", "kafka", "rabbitmq",
                            "database", "replicationlag", "downstreamerror", "upstreamtimeout"),
                    "alertname 含依赖组件关键词"),
            new Rule("INFRA-LABEL", IncidentCategory.INFRA, Target.DOMAIN_LABEL,
                    List.of("infra", "infrastructure"), "label domain/category=infra"),
            new Rule("INFRA-ALERTNAME", IncidentCategory.INFRA, Target.ALERTNAME_CONTAINS,
                    List.of("cpu", "memory", "oom", "disk", "inode", "filesystem",
                            "nodepressure", "hostdown"),
                    "alertname 含主机/资源关键词"),
            new Rule("APPLICATION-LABEL", IncidentCategory.APPLICATION, Target.DOMAIN_LABEL,
                    List.of("application", "app"), "label domain/category=application"),
            new Rule("APPLICATION-ALERTNAME", IncidentCategory.APPLICATION, Target.ALERTNAME_CONTAINS,
                    List.of("exception", "errorrate", "latency", "http5xx", "crashloop",
                            "restartloop", "deadlock", "statemachine"),
                    "alertname 含应用自身异常关键词"));

    /** 表序裁决：首条命中即返回；零命中 → UNCLASSIFIED fallback（依据如实写明） */
    public Classification classify(Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels");
        String alertname = normalize(labels.get("alertname"));
        String domain = normalize(firstPresent(labels, "domain", "category"));
        String service = normalize(firstPresent(labels, "service", "service_name"));
        for (Rule rule : RULES) {
            String haystack = switch (rule.target()) {
                case ALERTNAME_CONTAINS -> alertname;
                case DOMAIN_LABEL -> domain;
                case SERVICE_LABEL -> service;
            };
            if (haystack.isEmpty()) {
                continue;
            }
            for (String needle : rule.needles()) {
                boolean hit = rule.target() == Target.ALERTNAME_CONTAINS
                        ? haystack.contains(needle) : haystack.equals(needle);
                if (hit) {
                    return new Classification(rule.category(), rule.ruleId(), RULE_VERSION,
                            rule.basisTemplate() + "（命中 '" + needle + "'）");
                }
            }
        }
        return new Classification(IncidentCategory.UNCLASSIFIED, FALLBACK_RULE_ID,
                RULE_VERSION, "无规则命中，信息不足归类");
    }

    /** 归一化：截断 → 小写 → 剔除非 [a-z0-9]（null → 空串） */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String bounded = raw.length() > MAX_INPUT_CHARS
                ? raw.substring(0, MAX_INPUT_CHARS) : raw;
        String lower = bounded.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String firstPresent(Map<String, String> labels, String... keys) {
        for (String key : keys) {
            String value = labels.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
