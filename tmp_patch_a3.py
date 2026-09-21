# -*- coding: utf-8 -*-
import io

p = r'duty-adapter/src/main/java/com/objwww/pr/duty/webhook/DutyWebhookClient.java'
t = io.open(p, encoding='utf-8').read()

old = """    private static String quote(String value) {
        return "\\"" + value.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"")
                .replace("\\n", "\\\\n").replace("\\r", "") + "\\"";
    }"""
new = """    /** JSON 字符串转义：控制字符（TAB 等 <0x20 裸字符在 JSON string 非法）全部短形/
     * \\\\uXXXX 转义——裸 TAB 会让接收端 parse 拒绝整条通知 */
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\\\' -> sb.append("\\\\\\\\");
                case '"' -> sb.append("\\\\\\"");
                case '\\n' -> sb.append("\\\\n");
                case '\\r' -> sb.append("\\\\r");
                case '\\t' -> sb.append("\\\\t");
                case '\\b' -> sb.append("\\\\b");
                case '\\f' -> sb.append("\\\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }"""
assert old in t, 'quote pattern not found'
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('duty webhook ok')
