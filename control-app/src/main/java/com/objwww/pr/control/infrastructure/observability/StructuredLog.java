package com.objwww.pr.control.infrastructure.observability;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * 结构化事件日志（M3-27 最小集）：run/task/decision/latency 等关键面以单行 JSON
 * 字段落日志（event/ts 打头，字段集 = 调用点白名单）。
 *
 * <p>纪律：调用方只允许放标识符/状态/时长等<b>非内容</b>字段——ask 正文、secret、
 * 原始工具参数永不经由此处（§6.5 脱敏链与 FUT-09 的日志面防线；capture 测试钉死）。
 * 序列化失败只降级为事件名日志，绝不上抛打断业务链。
 */
public final class StructuredLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StructuredLog() {
    }

    /** fields 有序（LinkedHashMap）——canonical 字段序即日志可 diff 性 */
    public static void event(Logger logger, String event, Map<String, Object> fields) {
        try {
            LinkedHashMap<String, Object> body = new LinkedHashMap<>();
            body.put("event", event);
            body.put("ts", Instant.now().toString());
            body.putAll(fields);
            logger.info(JSON.writeValueAsString(body));
        } catch (Exception e) {
            logger.warn("structured log 序列化失败 event={}", event);
        }
    }
}
