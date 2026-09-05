package com.objwww.pr.control.alert.domain.tool;

import com.objwww.pr.shared.Digests;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范化 JSON（AM4 M4-15 前半）：递归按 key 字典序排序、零空白，
 * 产出与字段顺序无关的稳定串，供 action_digest 计算。
 *
 * <p>domain 零框架铁律（R3）下不引 Jackson：输入限定为<b>已解析的结构</b>
 * （Map&lt;String,?&gt; / List / String / Number / Boolean / null），
 * 本件不做 JSON 文本解析。Number 一律经 BigDecimal 归一（1、1.0、1e0 同形），
 * 浮点 -0.0 归一为 0。
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** 结构 → 规范串（key 字典序、无空白；非法类型抛 IllegalArgumentException） */
    public static String canonicalize(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    /** 结构 → 稳定 sha256（canonicalize 的摘要便捷方法） */
    public static String sha256(Object value) {
        return Digests.sha256Hex(canonicalize(value));
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b.booleanValue());
            case String s -> writeString(s, sb);
            case Number n -> sb.append(normalizeNumber(n));
            case Map<?, ?> m -> writeObject(m, sb);
            case List<?> list -> writeArray(list, sb);
            default -> throw new IllegalArgumentException(
                    "CanonicalJson 只接受 Map/List/String/Number/Boolean/null，实际: "
                            + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder sb) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "CanonicalJson 的 Map key 必须是 String，实际: " + e.getKey());
            }
            sorted.put(key, e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            writeString(e.getKey(), sb);
            sb.append(':');
            write(e.getValue(), sb);
            first = false;
        }
        sb.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(list.get(i), sb);
        }
        sb.append(']');
    }

    /** JSON 字符串转义（RFC 8259：引号/反斜杠/控制字符以 U+XXXX 转义形输出） */
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** 数值归一：BigDecimal 去尾零 plain 形（1 / 1.0 / 1e0 / 1.00 同输出 "1"；±0 → "0"） */
    private static String normalizeNumber(Number n) {
        BigDecimal bd = switch (n) {
            case BigDecimal b -> b;
            case java.math.BigInteger bi -> new BigDecimal(bi);
            default -> new BigDecimal(n.toString());
        };
        if (bd.signum() == 0) {
            return "0";
        }
        return bd.stripTrailingZeros().toPlainString();
    }
}
