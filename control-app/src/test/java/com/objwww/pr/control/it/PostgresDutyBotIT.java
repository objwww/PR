package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.infrastructure.persistence.PostgresDutyBotStore;
import com.objwww.pr.control.ops.dutybot.domain.DutyBotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PermissionDeniedDataAccessException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UX-02 验收（真 PG，failsafe *IT；本机无 docker 自动跳过——交付标 NOT_RUN）：
 * V83 chat_session/chat_message 列约束（role 词表 / intent 与 assistant 同生同灭 /
 * 幂等锚部分唯一 / FK）、授权矩阵（control_app 只增读、update/delete 拒、
 * publisher/notify/eval 显式拒）、insert-only、seq 游标分页、幂等重放配对锚。
 */
class PostgresDutyBotIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");

    private PostgresDutyBotStore store;

    @BeforeEach
    void setUpStore() {
        store = new PostgresDutyBotStore(controlJdbc, new ObjectMapper());
    }

    // ------------------------------------------------------------------ 约束

    @Test
    @DisplayName("V83 约束：role 词表 / intent 与 assistant 同生同灭 / client_id 只属于 user / 内容上限")
    void checkConstraints() {
        UUID sid = insertSession("op-a");
        // role='bot' 不带 intent——让 role 词表约束独触（带 HELP 时 intent 约束先评估，旧红实证）
        assertThatThrownBy(() -> rawMessage(sid, "bot", "x", null, null))
                .hasMessageContaining("ck_chat_message_role");
        // user 行带 intent 直拒；assistant 行缺 intent 直拒
        assertThatThrownBy(() -> rawMessage(sid, "user", "x", "HELP", null))
                .hasMessageContaining("ck_chat_message_intent");
        assertThatThrownBy(() -> rawMessage(sid, "assistant", "x", null, null))
                .hasMessageContaining("ck_chat_message_intent");
        // 幂等锚只属于用户行
        assertThatThrownBy(() -> rawMessage(sid, "assistant", "x", "HELP", "c-1"))
                .hasMessageContaining("ck_chat_message_client_id");
        // 内容空白 / 超 2000 直拒
        assertThatThrownBy(() -> rawMessage(sid, "user", "   ", null, null))
                .hasMessageContaining("ck_chat_message_content");
        assertThatThrownBy(() -> rawMessage(sid, "user", "x".repeat(2001), null, null))
                .hasMessageContaining("ck_chat_message_content");
        // FK：无会话直拒
        assertThatThrownBy(() -> rawMessage(UUID.randomUUID(), "user", "x", null, null))
                .hasMessageContaining("chat_message_session_id_fkey");
    }

    @Test
    @DisplayName("幂等锚 uq(session_id, client_message_id) 撞键直拒；空键不参与")
    void clientMessageIdUnique() {
        UUID sid = insertSession("op-a");
        rawMessage(sid, "user", "a", null, "c-dup");
        assertThatThrownBy(() -> rawMessage(sid, "user", "b", null, "c-dup"))
                .isInstanceOf(DuplicateKeyException.class);
        // 空键多行合法（a 落库、b 撞键被拒、c/d 空键各一行 → 恰 3 行）
        rawMessage(sid, "user", "c", null, null);
        rawMessage(sid, "user", "d", null, null);
        assertThat(store.countMessages(sid)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 授权矩阵

    @Test
    @DisplayName("授权矩阵：control_app 只增读（update/delete 拒）；publisher/notify/eval 显式拒")
    void grantMatrix() {
        UUID sid = insertSession("op-a");
        rawMessage(sid, "user", "今晚谁值班", null, "c-1");
        assertThat(store.findSession(sid)).isPresent();
        assertThat(store.listSessions("op-a", null, 10)).hasSize(1);

        // insert-only：control_app 零 update/delete 开口
        assertThatThrownBy(() -> controlJdbc.sql(
                "update chat_session set title = 'x' where id = :id")
                .param("id", sid).update())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> controlJdbc.sql(
                "delete from chat_message where session_id = :id")
                .param("id", sid).update())
                .hasStackTraceContaining("permission denied");

        // publisher/notify/eval 显式归零（读写皆拒）
        for (var jdbc : new org.springframework.jdbc.core.simple.JdbcClient[]{
                publisherJdbc, notifyJdbc, evalJdbc}) {
            assertThatThrownBy(() -> jdbc.sql(
                    "insert into chat_session (id, title, owner, created_at) values (:id, 't', 'x', now())")
                    .param("id", UUID.randomUUID()).update())
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> jdbc.sql(
                    "select id from chat_message where session_id = :id")
                    .param("id", sid).query(UUID.class).list())
                    .hasStackTraceContaining("permission denied");
        }
    }

    // ------------------------------------------------------------------ 分页与配对

    @Test
    @DisplayName("seq 游标分页：最新在前、严格小于续页、页间不重叠")
    void seqCursorPagination() {
        UUID sid = insertSession("op-a");
        for (int i = 0; i < 7; i++) {
            store.insertMessage(new DutyBotStore.NewMessage(UUID.randomUUID(), sid,
                    i % 2 == 0 ? "user" : "assistant", "m" + i,
                    i % 2 == 0 ? null : "HELP", List.of(), null, NOW.plusSeconds(i)));
        }
        List<DutyBotStore.MessageRow> page1 = store.listMessages(sid, null, 4);
        assertThat(page1).hasSize(4);
        assertThat(page1.get(0).seq()).isGreaterThan(page1.get(3).seq());
        List<DutyBotStore.MessageRow> page2 = store.listMessages(sid, page1.get(3).seq(), 4);
        assertThat(page2).hasSize(3);
        assertThat(page2.stream().map(DutyBotStore.MessageRow::seq))
                .allMatch(seq -> seq < page1.get(3).seq());
    }

    @Test
    @DisplayName("幂等重放配对：同键回查用户消息 + seq 之后首条 assistant；引用快照往返")
    void replayPairingAndRefsRoundTrip() {
        UUID sid = insertSession("op-a");
        UUID incidentId = UUID.randomUUID();
        DutyBotStore.MessageRow user = store.insertMessage(new DutyBotStore.NewMessage(
                UUID.randomUUID(), sid, "user", "这个告警什么情况", null, List.of(), "c-9", NOW));
        store.insertMessage(new DutyBotStore.NewMessage(UUID.randomUUID(), sid, "assistant",
                "告警 HighCpuUsage：……", "INCIDENT_DETAIL",
                List.of(new DutyBotStore.Ref("incident", incidentId.toString())), null, NOW));

        DutyBotStore.MessageRow found = store.findByClientMessageId(sid, "c-9").orElseThrow();
        assertThat(found.id()).isEqualTo(user.id());
        DutyBotStore.MessageRow reply = store.findReplyAfter(sid, found.seq()).orElseThrow();
        assertThat(reply.intent()).isEqualTo("INCIDENT_DETAIL");
        assertThat(reply.references())
                .containsExactly(new DutyBotStore.Ref("incident", incidentId.toString()));
    }

    // ------------------------------------------------------------------ 内部

    private UUID insertSession(String owner) {
        UUID id = UUID.randomUUID();
        store.insertSession(new DutyBotStore.SessionRow(id, "t", owner, NOW));
        return id;
    }

    private void rawMessage(UUID sessionId, String role, String content, String intent,
                            String clientMessageId) {
        controlJdbc.sql("""
                insert into chat_message (id, session_id, role, content, intent,
                    client_message_id, created_at)
                values (:id, :sid, :role, :content, :intent, :cmid, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("sid", sessionId)
                .param("role", role)
                .param("content", content)
                .param("intent", intent)
                .param("cmid", clientMessageId)
                .param("at", Timestamp.from(NOW))
                .update();
    }
}
