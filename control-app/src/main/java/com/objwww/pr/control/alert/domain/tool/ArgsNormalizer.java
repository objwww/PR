package com.objwww.pr.control.alert.domain.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具查询参数规范化器（MC24/P0-2，R7 方案 §18.3 能力 06/§21.2）：同参数不同措辞
 * （空白差异）的查询折叠为同一语义身份，供 {@link ActionDigest} 在摘要计算前接入
 * ——同 run 重复查询凭同 digest 复用已有证据行，不重打工具。
 *
 * <p><b>规范化范围白名单（钉死，逐字段扩面需评审）</b>：仅字符串值的空白折叠——
 * trim + 内部连续空白（\s+，含全角空格/换行/制表）折叠为单空格；递归作用于
 * Map/List 全层。以下差异<b>刻意不折叠</b>（语义敏感，折叠=扩大"同一查询"误判面）：
 * <ul>
 *   <li>大小写（"GET" vs "get"——PromQL/日志查询大小写敏感）；</li>
 *   <li>数字格式（"1.0" vs "1.00"——数值串按原文比较）；</li>
 *   <li>null / 非字符串标量（原样——canonical JSON 层已管键序与结构身份，null 与
 *       空容器的统一不属于空白折叠白名单）。</li>
 * </ul>
 * 键序无关由 {@link InternalCanonicalJsonV1} 承担（本类不重排列），空白折叠是
 * canonical JSON 之外唯一新增的语义规范化维度，因此 canonicalizationVersion 不变
 * ——对空白干净的存量参数，digest 逐字节不变（回放夹具不受影响）。
 */
public final class ArgsNormalizer {

    private ArgsNormalizer() {
    }

    /**
     * 入口：顶层即 {@link ActionEnvelope#args()} 的已解析结构
     * （Map/List/String/Number/Boolean/null），逐值递归折叠。
     */
    public static Object normalize(Object args) {
        if (args == null) {
            return null;
        }
        if (args instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), normalize(value)));
            return out;
        }
        if (args instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalize(item));
            }
            return out;
        }
        if (args instanceof String s) {
            return s.strip().replaceAll("\\s+", " ");
        }
        return args; // 数值/布尔等标量原样（白名单外不动）
    }
}
