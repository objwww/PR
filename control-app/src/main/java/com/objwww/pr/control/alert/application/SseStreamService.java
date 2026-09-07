package com.objwww.pr.control.alert.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 流服务（M5-13）：stream ticket 鉴权 + 增量排水。
 *
 * <p>实时鉴权 = stream ticket（TTL 30s、单次、绑 run+主体）——禁止 URL 带长效 token
 * （落码方案 §M5-13③ annot 原文）：前端先 POST 换短期票，再以票开
 * EventSource，重连带 Last-Event-ID 由服务端归一为 after_seq 游标。
 *
 * <p>排水语义（INV-AM5-8：断线/慢客户端不拖慢 Run）： drain 只做一次有界页读、
 * 即取即推、零阻塞——背压 = 慢客户端断开，由 Servlet 容器侧负责。真身走表
 * （PG NOTIFY 只作唤醒，§3.2/§8），本类不持有任何推送长任务。
 *
 * <p>gap 判定分工：EventQueryService 负责打 gap 标（含"客户端超窗"）；SSE 侧
 * 只把"游标落后仍现缺口/被清（cursor ≤ latestSeq）"视为 resync 事件——
 * SSE 游标来自服务端自签 id: seq，超窗只说明暂无新事件，心跳即可。
 */
public class SseStreamService {

    private static final Logger log = LoggerFactory.getLogger(SseStreamService.class);

    /** SSE 事件名固定；每条消息 id = 事件 seq（浏览器 Last-Event-ID 续传锚） */
    public static final String EVENT_NAME = "rca_event";
    /** 单次排水页上限（EventQueryService 内部再钳 200） */
    private static final int DRAIN_PAGE_LIMIT = 200;

    private final EventQueryService events;
    private final Duration ticketTtl;
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    /** @param ticketTtl 票存活期（落码方案定 30s） */
    public SseStreamService(EventQueryService events, Duration ticketTtl) {
        this.events = events;
        this.ticketTtl = ticketTtl;
    }

    private record Ticket(UUID runId, String subject, Instant expiresAt) {
    }

    /** 签发流票：绑 run+主体，TTL 内单次有效；顺手清出已过期票（防未消费票堆积） */
    public String issueTicket(UUID runId, String subject, Instant now) {
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(runId, subject, now.plus(ticketTtl)));
        return ticket;
    }

    /**
     * 消费流票：单次（无论是否通过校验即失效）、TTL 过期拒绝、绑 run+主体。
     * 失败不区分原因面（过期/绑错/重放）——对探测者零信息。
     */
    public boolean consumeTicket(String ticket, UUID runId, String subject, Instant now) {
        Ticket t = tickets.remove(ticket);
        return t != null
                && now.isAfter(t.expiresAt()) == false
                && t.runId().equals(runId)
                && t.subject().equals(subject);
    }

    /** SSE 接收面：与 Servlet 依赖解耦（UT 无容器可测；控制器侧包 SseEmitter） */
    public interface Sink {
        /** 投递一条事件：id=seq（Last-Event-ID 锚）、event 固定 rca_event、data=帧信封{seq,type,taskId,summary,payload 白名单} */
        void accept(String id, String event, Map<String, Object> data);

        /** 缺口/游标过期 → 停止增量，客户端全量重同步（Unleash delta API 先例） */
        void resync(long latestSeq);

        /** 空轮询心跳（保活 + 客户端对账 latestSeq 锚） */
        void heartbeat();
    }

    /**
     * 排水：把 (cursor, 服务端最新] 的事件经 Sink 推出，返回新游标。
     * resync / 空轮询时游标原样返回（客户端重同步后从 0 重放或维持心跳节奏）。
     */
    public long drain(UUID runId, long cursor, Sink sink, Instant now) {
        EventQueryService.EventPage page =
                events.events(runId, cursor, DRAIN_PAGE_LIMIT);
        if (page.gap() && cursor <= page.latestSeq()) {
            log.warn("sse drain gap: runId={} cursor={} latestSeq={} → resync",
                    runId, cursor, page.latestSeq());
            sink.resync(page.latestSeq());
            return cursor;
        }
        if (page.events().isEmpty()) {
            sink.heartbeat();
            return cursor;
        }
        long last = cursor;
        for (EventQueryService.EventItem item : page.events()) {
            // 帧信封对齐 mocks 事件行形状 {seq,type,taskId,summary}；payload 保持白名单子面
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("seq", item.seq());
            frame.put("type", item.eventType());
            frame.put("taskId", item.taskId());
            frame.put("summary", item.summary());
            frame.put("payload", item.payload());
            sink.accept(String.valueOf(item.seq()), EVENT_NAME, frame);
            last = item.seq();
        }
        return last;
    }
}
