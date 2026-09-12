package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;

/**
 * rca_model_input 的 Postgres 实现（R2，V90 append-only）：一逻辑模型调用一捕获行
 * （id = model_call_id 同体主键），insert-only 无 update/delete 授权面。
 */
public class PostgresRcaModelInputCapture implements RcaModelInputCapture {

    private final JdbcClient jdbc;
    private final Level level;

    public PostgresRcaModelInputCapture(JdbcClient jdbc, Level level) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public Level level() {
        return level;
    }

    @Override
    public void capture(CaptureRow row) {
        jdbc.sql("""
                insert into rca_model_input (id, model_call_id, capture_level, prompt_text,
                    prompt_digest, message_bytes, approx_tokens, redaction_note, created_at)
                values (:id, :modelCallId, :captureLevel, :promptText, :promptDigest,
                    :messageBytes, :approxTokens, :redactionNote, now())
                """)
                .param("id", row.modelCallId())
                .param("modelCallId", row.modelCallId())
                .param("captureLevel", row.level().name())
                .param("promptText", row.promptText())
                .param("promptDigest", row.promptDigest())
                .param("messageBytes", row.messageBytes())
                .param("approxTokens", row.approxTokens())
                .param("redactionNote", row.redactionNote())
                .update();
    }
}
