package com.objwww.pr.control.ops.dutybot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UX-02 仿真会话存储端口（V83 chat_session/chat_message；control_app 只增读）。
 *
 * <p>insert-only：端口无 update/delete 方法——会话无改名/删除面，消息无编辑面。
 * 消息排序与分页游标 = seq（identity 单调插入序）；幂等重放配对锚 =
 * 同 session 内 seq 严格大于用户消息的首条 assistant 行。
 */
public interface DutyBotStore {

    /** 会话行 */
    record SessionRow(UUID id, String title, String owner, Instant createdAt) {
    }

    /** 消息行（references = 引用实体快照 [{type, id}]，无引用 = 空表） */
    record MessageRow(long seq, UUID id, UUID sessionId, String role, String content,
                      String intent, List<Ref> references, String clientMessageId,
                      Instant createdAt) {
    }

    /** 引用实体（type=incident/notify_outbox/…，id=实体 id 字符串，供前端跳转） */
    record Ref(String type, String id) {
    }

    /** 新消息（seq 由存储面铸造——DB identity / fake 计数器） */
    record NewMessage(UUID id, UUID sessionId, String role, String content,
                      String intent, List<Ref> references, String clientMessageId,
                      Instant createdAt) {
    }

    /** 会话列表键集游标（(created_at, id) 严格小于继续取页） */
    record SessionCursor(Instant at, UUID id) {
    }

    UUID insertSession(SessionRow session);

    Optional<SessionRow> findSession(UUID sessionId);

    /** 本人会话列表（created_at desc, id desc；cursor=null 首页） */
    List<SessionRow> listSessions(String owner, SessionCursor cursor, int limit);

    long countMessages(UUID sessionId);

    /** 幂等锚查询（uq(session_id, client_message_id) 部分唯一索引面） */
    Optional<MessageRow> findByClientMessageId(UUID sessionId, String clientMessageId);

    /** 重放配对：session 内 seq &gt; afterSeq 的首条 assistant 消息 */
    Optional<MessageRow> findReplyAfter(UUID sessionId, long afterSeq);

    /** 插入（seq 由实现铸造并随返回行带回） */
    MessageRow insertMessage(NewMessage message);

    /** 会话内消息流（seq desc 最新在前；cursorSeq=上一页末行 seq，严格小于继续取） */
    List<MessageRow> listMessages(UUID sessionId, Long cursorSeq, int limit);
}
