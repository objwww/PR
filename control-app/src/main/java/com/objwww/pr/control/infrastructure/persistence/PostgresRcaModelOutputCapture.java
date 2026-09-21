package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.agent.RcaModelOutputCapture;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

/**
 * rca_model_output 的 Postgres 实现（V167 append-only）：一逻辑模型调用一捕获行
 * （id = model_call_id 同体主键），insert-only 无 update/delete 授权面——
 * 与 PostgresRcaModelInputCapture（V90）逐行对称。
 */
public class PostgresRcaModelOutputCapture implements RcaModelOutputCapture {

    private final JdbcClient jdbc;
    private final Level level;

    public PostgresRcaModelOutputCapture(JdbcClient jdbc, Level level) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.level = Objects.requireNonNull(level, "level");
        if (level == Level.OFF) {
            // OFF 由 RcaModelOutputCapture.OFF 单例承载；装配侧不得把零行档落库化
            throw new IllegalArgumentException("OFF 档不落行，勿装配 Postgres 写面");
        }
    }

    @Override
    public Level level() {
        return level;
    }

    @Override
    public void capture(CaptureRow row) {
        jdbc.sql("""
                insert into rca_model_output (id, model_call_id, capture_level, output_text,
                    output_digest, message_bytes, approx_tokens, redaction_note, created_at)
                values (:id, :modelCallId, :captureLevel, :outputText, :outputDigest,
                    :messageBytes, :approxTokens, :redactionNote, now())
                """)
                .param("id", row.modelCallId())
                .param("modelCallId", row.modelCallId())
                .param("captureLevel", row.level().name())
                .param("outputText", row.outputText())
                .param("outputDigest", row.outputDigest())
                .param("messageBytes", row.messageBytes())
                .param("approxTokens", row.approxTokens())
                .param("redactionNote", row.redactionNote())
                .update();
    }
}
