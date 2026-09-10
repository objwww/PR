package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * EV-05 受限日志比较的纯函数段（EvalQueryService 单测同模式，假输入可测）：
 * 从 logs.query 冻结证据 payload（EX-B2 统一形状
 * {@code {"status":"success","data":{"result":[{ts,service,line}…],"truncated":bool}}}）
 * 抽取错误行 → 归一化签名分桶 → 两侧 diff。
 *
 * <p>纪律：
 * <ul>
 *   <li>输入全程有界（每证据最多扫 {@value #MAX_LINES_PER_EVIDENCE} 行、
 *       每侧签名表 {@value #MAX_SIGNATURES} 上限、签名截
 *       {@value #MAX_SIGNATURE_CHARS} 字符）——冻结 payload 写期已有界，
 *       读面仍自备闸（防御性双层）；</li>
 *   <li>payload 形状不符（非统一形状/解析失败）→ shapeOk=false 显式标记，
 *       不当空结果吞掉；</li>
 *   <li>签名归一化只为聚合对比服务（去时间戳/数字/UUID/hex 动态段），
 *       原始行不出本读面（日志原文属于证据快照，本端点只出摘要桶）。</li>
 * </ul>
 */
final class EvalLogCompare {

    static final int MAX_LINES_PER_EVIDENCE = 2_000;
    static final int MAX_SIGNATURES = 200;
    static final int MAX_SIGNATURE_CHARS = 160;
    /** topSignatures 输出上限（每案例摘要面） */
    static final int TOP_SIGNATURES = 20;

    /** 错误行判定（大小写不敏感子串——驼峰异常名 NullPointerException 也要命中；
     *  召回优先：terror 类误命中可接受，签名聚合只为对比摘要服务） */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "error|exception|fatal|panic|severe|traceback", Pattern.CASE_INSENSITIVE);

    private static final Pattern ISO_TS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_LIKE = Pattern.compile(
            "\\b(0x[0-9a-fA-F]+|[0-9a-fA-F]{16,})\\b");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private EvalLogCompare() {
    }

    /** 单条日志行（ts/service 可空 = 源行缺字段如实） */
    record LogLine(Instant ts, String service, String text) {
    }

    /** 一条证据 payload 的抽取结果（shapeOk=false = 形状不符显式标记） */
    record Extraction(List<LogLine> lines, boolean truncated, boolean shapeOk) {
    }

    /** 签名桶（count=该签名错误行数） */
    record SignatureCount(String signature, long count) {
    }

    /** 计数变化桶（两侧同签名但计数不同） */
    record SignatureChange(String signature, long baselineCount, long candidateCount) {
    }

    /** 两侧签名表 diff（only* 按 count DESC + signature 稳定序；截 top N 由调用方面决定） */
    record Diff(List<SignatureCount> onlyInBaseline, List<SignatureCount> onlyInCandidate,
                List<SignatureChange> increased, List<SignatureChange> decreased,
                long unchanged) {
    }

    /** logs.query 统一形状 payload → 行序列；解析失败/形状不符 → shapeOk=false、空行表 */
    static Extraction extract(ObjectMapper mapper, String payloadJson) {
        if (payloadJson == null) {
            return new Extraction(List.of(), false, false);
        }
        JsonNode root;
        try {
            root = mapper.readTree(payloadJson);
        } catch (Exception e) {
            return new Extraction(List.of(), false, false);
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) {
            return new Extraction(List.of(), false, false);
        }
        List<LogLine> lines = new ArrayList<>();
        for (JsonNode entry : result) {
            if (lines.size() >= MAX_LINES_PER_EVIDENCE) {
                break;
            }
            if (!entry.isObject() || !entry.path("line").isTextual()) {
                continue;
            }
            Instant ts = null;
            JsonNode tsNode = entry.get("ts");
            if (tsNode != null && tsNode.isTextual()) {
                try {
                    ts = Instant.parse(tsNode.asText());
                } catch (Exception e) {
                    ts = null; // 时间戳不可解析如实 null，行本身仍入面
                }
            }
            String service = entry.path("service").isTextual()
                    ? entry.path("service").asText() : null;
            lines.add(new LogLine(ts, service, entry.path("line").asText()));
        }
        return new Extraction(List.copyOf(lines), root.path("data").path("truncated")
                .asBoolean(false), true);
    }

    static boolean isErrorLine(String line) {
        return line != null && ERROR_PATTERN.matcher(line).find();
    }

    /** 错误行 → 归一化签名（去动态段；截断有界）；非错误行不入签名面 */
    static String signatureOf(String line) {
        String s = ISO_TS.matcher(line).replaceAll("<ts>");
        s = UUID_LIKE.matcher(s).replaceAll("<uuid>");
        s = HEX_LIKE.matcher(s).replaceAll("<hex>");
        s = NUMBERS.matcher(s).replaceAll("<n>");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        if (s.length() > MAX_SIGNATURE_CHARS) {
            s = s.substring(0, MAX_SIGNATURE_CHARS);
        }
        return s;
    }

    /** 行序列 → 签名桶表（signature → count；超 {@value #MAX_SIGNATURES} 个签名即截，
     *  保留高频桶——truncatedSignatures 由调用方据桶数差判） */
    static Map<String, Long> bucketBySignature(List<LogLine> lines) {
        Map<String, Long> counts = new TreeMap<>();
        for (LogLine line : lines) {
            if (isErrorLine(line.text())) {
                counts.merge(signatureOf(line.text()), 1L, Long::sum);
            }
        }
        if (counts.size() <= MAX_SIGNATURES) {
            return counts;
        }
        // 超限：保留 count 最高者（同 count 取 signature 字典序小者，稳定落点）
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        Map<String, Long> kept = new TreeMap<>();
        for (Map.Entry<String, Long> e : entries.subList(0, MAX_SIGNATURES)) {
            kept.put(e.getKey(), e.getValue());
        }
        return kept;
    }

    /** top N 签名桶（count DESC + signature ASC 稳定序） */
    static List<SignatureCount> top(Map<String, Long> buckets, int n) {
        List<SignatureCount> all = new ArrayList<>(buckets.size());
        for (Map.Entry<String, Long> e : buckets.entrySet()) {
            all.add(new SignatureCount(e.getKey(), e.getValue()));
        }
        all.sort(Comparator.comparingLong(SignatureCount::count).reversed()
                .thenComparing(SignatureCount::signature));
        return List.copyOf(all.subList(0, Math.min(n, all.size())));
    }

    /** 两侧签名表 diff（四桶 + unchanged 计数；输出有界按 top N 由调用方截） */
    static Diff diff(Map<String, Long> baseline, Map<String, Long> candidate, int topN) {
        List<SignatureCount> onlyBaseline = new ArrayList<>();
        List<SignatureCount> onlyCandidate = new ArrayList<>();
        List<SignatureChange> increased = new ArrayList<>();
        List<SignatureChange> decreased = new ArrayList<>();
        long unchanged = 0;
        for (Map.Entry<String, Long> e : baseline.entrySet()) {
            Long other = candidate.get(e.getKey());
            if (other == null) {
                onlyBaseline.add(new SignatureCount(e.getKey(), e.getValue()));
            } else if (other < e.getValue()) {
                decreased.add(new SignatureChange(e.getKey(), e.getValue(), other));
            } else if (other > e.getValue()) {
                increased.add(new SignatureChange(e.getKey(), e.getValue(), other));
            } else {
                unchanged++;
            }
        }
        for (Map.Entry<String, Long> e : candidate.entrySet()) {
            if (!baseline.containsKey(e.getKey())) {
                onlyCandidate.add(new SignatureCount(e.getKey(), e.getValue()));
            }
        }
        Comparator<SignatureCount> byCount = Comparator.comparingLong(SignatureCount::count)
                .reversed().thenComparing(SignatureCount::signature);
        onlyBaseline.sort(byCount);
        onlyCandidate.sort(byCount);
        Comparator<SignatureChange> byDelta = Comparator
                .comparingLong((SignatureChange c) -> Math.abs(c.candidateCount() - c.baselineCount()))
                .reversed().thenComparing(SignatureChange::signature);
        increased.sort(byDelta);
        decreased.sort(byDelta);
        return new Diff(cap(onlyBaseline, topN), cap(onlyCandidate, topN),
                cap(increased, topN), cap(decreased, topN), unchanged);
    }

    private static <T> List<T> cap(List<T> items, int n) {
        return List.copyOf(items.subList(0, Math.min(n, items.size())));
    }
}
