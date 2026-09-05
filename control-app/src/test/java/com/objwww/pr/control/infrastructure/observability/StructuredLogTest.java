package com.objwww.pr.control.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** M3-27：结构化事件单行 JSON、canonical 字段序、序列化失败降级不上抛。 */
class StructuredLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("structured-log-test");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String lastLine() {
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    @Test
    @DisplayName("event/ts 打头 + 调用字段序保持；输出单行 JSON")
    void singleLineJsonWithCanonicalFieldOrder() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run_id", "r-1");
        fields.put("decision", "COMPLETED");
        fields.put("latency_ms", 42L);

        StructuredLog.event(logger, "rca_task_decision", fields);

        String line = lastLine();
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        JsonNode parsed = mapper.readTree(line);
        assertThat(parsed.get("event").asText()).isEqualTo("rca_task_decision");
        Iterator<String> names = parsed.fieldNames();
        assertThat(names.next()).isEqualTo("event");
        assertThat(names.next()).isEqualTo("ts");
        assertThat(names.next()).isEqualTo("run_id");
        assertThat(names.next()).isEqualTo("decision");
        assertThat(names.next()).isEqualTo("latency_ms");
        assertThat(parsed.get("latency_ms").asLong()).isEqualTo(42L);
    }

    @Test
    @DisplayName("特殊字符经 JSON 转义，roundtrip 无损且日志仍单行")
    void escapingRoundTrip() throws Exception {
        Map<String, Object> fields = Map.of("secret_like", "quote\" and\nnewline\ttab");

        StructuredLog.event(logger, "e", fields);

        String line = lastLine();
        assertThat(line).doesNotContain("\n");
        JsonNode parsed = mapper.readTree(line);
        assertThat(parsed.get("secret_like").asText()).isEqualTo("quote\" and\nnewline\ttab");
    }

    @Test
    @DisplayName("null 值字段诚实落 JSON null")
    void nullFieldValue() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("error", null);

        StructuredLog.event(logger, "e", fields);

        assertThat(mapper.readTree(lastLine()).get("error").isNull()).isTrue();
    }

    @Test
    @DisplayName("不可序列化字段：降级为事件名日志，绝不上抛")
    void unserializableFallsBackWithoutThrowing() {
        Map<String, Object> fields = Map.of("bad", new Object());

        assertThatCode(() -> StructuredLog.event(logger, "e", fields)).doesNotThrowAnyException();

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("structured log");
        });
    }
}
