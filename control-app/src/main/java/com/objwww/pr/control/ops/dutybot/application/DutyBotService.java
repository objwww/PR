package com.objwww.pr.control.ops.dutybot.application;

import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import com.objwww.pr.control.ops.dutybot.domain.BotIntent;
import com.objwww.pr.control.ops.dutybot.domain.BotIntentRecognizer;
import com.objwww.pr.control.ops.dutybot.domain.BotReplyComposer;
import com.objwww.pr.control.ops.dutybot.domain.DutyBotStore;
import com.objwww.pr.control.ops.dutybot.domain.NotifyStatusReader;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * UX-02 值班仿真机器人对话服务（方案 §六 / UX 迭代方案 §二）：
 * 发用户消息 → 同步识别意图 → 读真实值班表/incident/outbox → 生成确定性回复
 * → 用户消息与机器人回复<b>同事务落库</b>（TransactionOperations 单 execute）。
 *
 * <p>纪律：
 * <ul>
 *   <li>actor=认证主体（controller 传入），会话按 owner 隔离——越权一律
 *       {@code Optional.empty}（404 面，不泄露存在性）；</li>
 *   <li>边界：消息 ≤ {@value #MAX_CONTENT_CHARS} 字符、每会话 ≤
 *       {@value #MAX_MESSAGES_PER_SESSION} 条、每用户 {@value #RATE_LIMIT_PER_MINUTE}
 *       条/分钟（固定窗口简单计数，进程内——多实例不共享，如实记为已知取舍）；</li>
 *   <li>幂等：clientMessageId 同会话同键重放 → 原用户消息 + 配对回复原样返回
 *       （replayed=true，零二次落库）；</li>
 *   <li>回复只引用真实查询结果，无数据如实"查询无结果"；仿真不回写真实业务状态
 *       （无 CaseCommand/通知副作用——UX-02 卡红线）。</li>
 * </ul>
 */
public class DutyBotService {

    /** 用户消息长度上限（字符） */
    public static final int MAX_CONTENT_CHARS = 400;
    /** 每会话消息总数上限（user+assistant 合计） */
    public static final int MAX_MESSAGES_PER_SESSION = 200;
    /** 频率限制：每用户每分钟用户消息条数（固定窗口） */
    public static final int RATE_LIMIT_PER_MINUTE = 30;
    /** 告警列表/通知异常行的 Top N */
    static final int TOP_N = 5;

    /** 频率超限（429 面） */
    public static final class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    /** 会话消息数达上限（409 面） */
    public static final class SessionFullException extends RuntimeException {
        public SessionFullException(String message) {
            super(message);
        }
    }

    /** 发消息结果：replayed=true = 幂等重放（原行返回，零二次落库） */
    public record PostResult(DutyBotStore.MessageRow userMessage,
                             DutyBotStore.MessageRow botMessage, boolean replayed) {
    }

    private final DutyBotStore store;
    private final DutyStore dutyStore;
    private final IncidentQueryReader incidentReader;
    private final NotifyStatusReader notifyReader;
    private final TransactionOperations tx;
    private final Supplier<Instant> now;
    private final ZoneId opsZone;
    private final RateLimiter rateLimiter;

    public DutyBotService(DutyBotStore store, DutyStore dutyStore,
                          IncidentQueryReader incidentReader, NotifyStatusReader notifyReader,
                          TransactionOperations tx, Supplier<Instant> now, ZoneId opsZone) {
        this.store = Objects.requireNonNull(store, "store");
        this.dutyStore = Objects.requireNonNull(dutyStore, "dutyStore");
        this.incidentReader = Objects.requireNonNull(incidentReader, "incidentReader");
        this.notifyReader = Objects.requireNonNull(notifyReader, "notifyReader");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.now = Objects.requireNonNull(now, "now");
        this.opsZone = Objects.requireNonNull(opsZone, "opsZone");
        this.rateLimiter = new RateLimiter();
    }

    // ------------------------------------------------------------------ 会话

    /** 建会话（title 可空 → 默认；超长截断 80，对齐 V83 CHECK） */
    public DutyBotStore.SessionRow createSession(String owner, String title) {
        requireOwner(owner);
        String effective = title == null || title.isBlank() ? "值班仿真会话" : title.strip();
        if (effective.length() > 80) {
            effective = effective.substring(0, 80);
        }
        DutyBotStore.SessionRow session = new DutyBotStore.SessionRow(
                UUID.randomUUID(), effective, owner, now.get());
        store.insertSession(session);
        return session;
    }

    /** 本人会话列表（cursor=null 首页；limit 已由 controller 收敛） */
    public List<DutyBotStore.SessionRow> listSessions(String owner,
                                                      DutyBotStore.SessionCursor cursor,
                                                      int limit) {
        requireOwner(owner);
        return store.listSessions(owner, cursor, limit);
    }

    /** 会话消息流；不存在或非本人 → empty（404 面） */
    public Optional<List<DutyBotStore.MessageRow>> listMessages(UUID sessionId, String owner,
                                                                Long cursorSeq, int limit) {
        requireOwner(owner);
        return owned(sessionId, owner).map(s -> store.listMessages(sessionId, cursorSeq, limit));
    }

    // ------------------------------------------------------------------ 发消息

    /**
     * 发用户消息并同步生成机器人回复（两条消息同事务落库）。
     * 会话不存在/非本人 → empty（404）；长度/频率/条数越界 → 对应异常（400/429/409），
     * 全部在落库前拒绝（零副作用）。
     */
    public Optional<PostResult> postMessage(UUID sessionId, String owner, String content,
                                            String clientMessageId) {
        requireOwner(owner);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 必填（非空白）");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException(
                    "content 超长（上限 " + MAX_CONTENT_CHARS + " 字符，实际 " + content.length() + "）");
        }
        if (clientMessageId != null && clientMessageId.isBlank()) {
            clientMessageId = null;
        }
        Optional<DutyBotStore.SessionRow> session = owned(sessionId, owner);
        if (session.isEmpty()) {
            return Optional.empty();
        }

        // 幂等重放：原用户消息 + 配对回复原样返回（零二次落库、不计频率）
        if (clientMessageId != null) {
            Optional<DutyBotStore.MessageRow> existing =
                    store.findByClientMessageId(sessionId, clientMessageId);
            if (existing.isPresent()) {
                DutyBotStore.MessageRow user = existing.get();
                DutyBotStore.MessageRow reply = store
                        .findReplyAfter(sessionId, user.seq()).orElse(null);
                return Optional.of(new PostResult(user, reply, true));
            }
        }

        // 频率限制（固定窗口简单计数；先于条数检查——刷接口不耗会话条数）
        if (!rateLimiter.tryAcquire(owner, now.get())) {
            throw new RateLimitExceededException(
                    "发送过于频繁（每用户每分钟最多 " + RATE_LIMIT_PER_MINUTE + " 条）");
        }
        // 会话条数上限（user+assistant 合计；到顶即封会话，需新建会话继续）
        if (store.countMessages(sessionId) >= MAX_MESSAGES_PER_SESSION) {
            throw new SessionFullException(
                    "会话消息数已达上限 " + MAX_MESSAGES_PER_SESSION + " 条，请新建会话");
        }

        LocalDate today = now.get().atZone(opsZone).toLocalDate();
        BotIntent intent = BotIntentRecognizer.recognize(content, today);
        BotReplyComposer.Reply reply = compose(intent);
        Instant at = now.get();

        String finalClientMessageId = clientMessageId;
        return Optional.of(tx.execute(status -> {
            DutyBotStore.MessageRow user = store.insertMessage(new DutyBotStore.NewMessage(
                    UUID.randomUUID(), sessionId, "user", content, null, List.of(),
                    finalClientMessageId, at));
            DutyBotStore.MessageRow bot = store.insertMessage(new DutyBotStore.NewMessage(
                    UUID.randomUUID(), sessionId, "assistant", reply.text(), reply.intent(),
                    reply.refs(), null, now.get()));
            return new PostResult(user, bot, false);
        }));
    }

    /** 按意图读真实数据并组装回复（只读——零写面） */
    private BotReplyComposer.Reply compose(BotIntent intent) {
        return switch (intent.kind()) {
            case DUTY_ONCALL -> {
                DutyScheduleSnapshot snapshot = dutyStore.loadSnapshot();
                yield BotReplyComposer.duty(intent, now.get(), snapshot);
            }
            case INCIDENT_QUERY -> {
                IncidentQueryReader.IncidentPage page = incidentReader.listIncidents(
                        intent.status(), null, intent.service(), null, null, null, TOP_N);
                yield BotReplyComposer.incidents(intent, page, now.get());
            }
            case INCIDENT_DETAIL -> BotReplyComposer.incidentDetail(intent.incidentId(),
                    incidentReader.detail(intent.incidentId()).orElse(null), now.get());
            case NOTIFY_STATUS -> BotReplyComposer.notifyStatus(notifyReader.summarize(TOP_N));
            case HELP -> BotReplyComposer.help();
            case UNSUPPORTED -> BotReplyComposer.unsupported();
        };
    }

    // ------------------------------------------------------------------ 内部

    private Optional<DutyBotStore.SessionRow> owned(UUID sessionId, String owner) {
        return store.findSession(sessionId).filter(s -> s.owner().equals(owner));
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 缺失（认证面缺陷，不放行）");
        }
    }

    /**
     * 固定窗口频率计数（进程内）：每 owner 60s 窗口最多 RATE_LIMIT_PER_MINUTE 条。
     * 多实例不共享、重启清零——已知取舍（演示面够用；真抗刷靠网关，文档如实记）。
     */
    static final class RateLimiter {
        private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

        boolean tryAcquire(String owner, Instant now) {
            long windowStart = now.getEpochSecond() / 60;
            long[] cell = windows.compute(owner, (k, cur) ->
                    cur == null || cur[0] != windowStart ? new long[]{windowStart, 0} : cur);
            synchronized (cell) {
                if (cell[1] >= RATE_LIMIT_PER_MINUTE) {
                    return false;
                }
                cell[1]++;
                return true;
            }
        }
    }
}
