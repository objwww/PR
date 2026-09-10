package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.ops.dutybot.domain.DutyBotStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V83 chat_session/chat_message 存取（UX-02；control_app 身份，只增读）。
 *
 * <p>seq = identity（INSERT 走 DEFAULT，实现与调用方都不传值）；消息游标 = seq
 * 严格小于继续取页（desc 最新在前）。references_json 进出经 ObjectMapper
 * （jsonb cast 沿 PostgresAlertInboxRepository 同式）。幂等锚靠
 * uq_chat_message_client 部分唯一索引兜底——并发同键第二写直接拒（唯一冲突），
 * 应用面先查后写在事务内完成。
 */
public class PostgresDutyBotStore implements DutyBotStore {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PostgresDutyBotStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public UUID insertSession(SessionRow session) {
        jdbc.sql("""
                insert into chat_session (id, title, owner, created_at)
                values (:id, :title, :owner, :at)
                """)
                .param("id", session.id())
                .param("title", session.title())
                .param("owner", session.owner())
                .param("at", Timestamp.from(session.createdAt()))
                .update();
        return session.id();
    }

    @Override
    public Optional<SessionRow> findSession(UUID sessionId) {
        return jdbc.sql("select id, title, owner, created_at from chat_session where id = :id")
                .param("id", sessionId)
                .query((rs, i) -> sessionRow(rs))
                .optional();
    }

    @Override
    public List<SessionRow> listSessions(String owner, SessionCursor cursor, int limit) {
        String sql = """
                select id, title, owner, created_at from chat_session
                where owner = :owner
                """ + (cursor == null ? "" : """
                and (created_at, id) < (:cat, :cid)
                """) + """
                order by created_at desc, id desc limit :limit
                """;
        var spec = jdbc.sql(sql).param("owner", owner).param("limit", limit);
        if (cursor != null) {
            spec = spec.param("cat", Timestamp.from(cursor.at())).param("cid", cursor.id());
        }
        return spec.query((rs, i) -> sessionRow(rs)).list();
    }

    @Override
    public long countMessages(UUID sessionId) {
        return jdbc.sql("select count(*) from chat_message where session_id = :sid")
                .param("sid", sessionId).query(Long.class).single();
    }

    @Override
    public Optional<MessageRow> findByClientMessageId(UUID sessionId, String clientMessageId) {
        return jdbc.sql("""
                select seq, id, session_id, role, content, intent, references_json,
                       client_message_id, created_at
                from chat_message
                where session_id = :sid and client_message_id = :cmid
                """)
                .param("sid", sessionId).param("cmid", clientMessageId)
                .query((rs, i) -> messageRow(rs))
                .optional();
    }

    @Override
    public Optional<MessageRow> findReplyAfter(UUID sessionId, long afterSeq) {
        return jdbc.sql("""
                select seq, id, session_id, role, content, intent, references_json,
                       client_message_id, created_at
                from chat_message
                where session_id = :sid and role = 'assistant' and seq > :seq
                order by seq limit 1
                """)
                .param("sid", sessionId).param("seq", afterSeq)
                .query((rs, i) -> messageRow(rs))
                .optional();
    }

    @Override
    public MessageRow insertMessage(NewMessage message) {
        return jdbc.sql("""
                insert into chat_message (id, session_id, role, content, intent,
                    references_json, client_message_id, created_at)
                values (:id, :sid, :role, :content, :intent,
                    cast(:refs as jsonb), :cmid, :at)
                returning seq, id, session_id, role, content, intent, references_json,
                    client_message_id, created_at
                """)
                .param("id", message.id())
                .param("sid", message.sessionId())
                .param("role", message.role())
                .param("content", message.content())
                .param("intent", message.intent())
                .param("refs", refsJson(message.references()))
                .param("cmid", message.clientMessageId())
                .param("at", Timestamp.from(message.createdAt()))
                .query((rs, i) -> messageRow(rs))
                .single();
    }

    @Override
    public List<MessageRow> listMessages(UUID sessionId, Long cursorSeq, int limit) {
        String sql = """
                select seq, id, session_id, role, content, intent, references_json,
                       client_message_id, created_at
                from chat_message where session_id = :sid
                """ + (cursorSeq == null ? "" : "and seq < :seq ") + """
                order by seq desc limit :limit
                """;
        var spec = jdbc.sql(sql).param("sid", sessionId).param("limit", limit);
        if (cursorSeq != null) {
            spec = spec.param("seq", cursorSeq);
        }
        return spec.query((rs, i) -> messageRow(rs)).list();
    }

    // ------------------------------------------------------------------ 内部

    private SessionRow sessionRow(ResultSet rs) throws SQLException {
        return new SessionRow(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("owner"), ts(rs, "created_at"));
    }

    private MessageRow messageRow(ResultSet rs) throws SQLException {
        return new MessageRow(rs.getLong("seq"), rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getString("role"),
                rs.getString("content"), rs.getString("intent"),
                parseRefs(rs.getString("references_json")),
                rs.getString("client_message_id"), ts(rs, "created_at"));
    }

    private String refsJson(List<Ref> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(refs);
        } catch (Exception e) {
            throw new IllegalArgumentException("references 无法序列化为 jsonb", e);
        }
    }

    private List<Ref> parseRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, Ref.class));
        } catch (Exception e) {
            throw new IllegalStateException("references_json 反序列化失败", e);
        }
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
