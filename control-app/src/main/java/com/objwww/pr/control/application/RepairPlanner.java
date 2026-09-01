package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.repository.RepairRequestRepository;
import com.objwww.pr.control.domain.service.RepairCommandFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 公平扫描修复单；CAS 读取/payload 构造在事务外，dispatch 仅做 DB 短事务。 */
public final class RepairPlanner {
    private static final Logger log = LoggerFactory.getLogger(RepairPlanner.class);
    private final RepairRequestRepository requests;
    private final ArtifactStore cas;
    private final RepairCommandFactory factory;
    private final RepairDispatchService dispatcher;
    private final int limit;
    private final long idleSleepMs;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public RepairPlanner(RepairRequestRepository requests, ArtifactStore cas,
                         RepairCommandFactory factory, RepairDispatchService dispatcher,
                         int limit, long idleSleepMs) {
        this.requests = Objects.requireNonNull(requests); this.cas = Objects.requireNonNull(cas);
        this.factory = Objects.requireNonNull(factory); this.dispatcher = Objects.requireNonNull(dispatcher);
        this.limit = limit; this.idleSleepMs = idleSleepMs;
    }

    public int runOnce() {
        int handled = 0;
        for (var candidate : requests.findReady(limit)) {
            try {
                byte[] payload = cas.get(candidate.payloadHash())
                        .orElseThrow(() -> new PayloadMissingException());
                byte[] basePayload = cas.get(candidate.basePayloadHash())
                        .orElseThrow(() -> new PayloadMissingException());
                if (dispatcher.dispatch(candidate.requestId(),
                        factory.prepare(candidate, payload, basePayload))) handled++;
            } catch (PayloadMissingException e) {
                // CAS 按 digest 寻址：get 返回 empty = 确定性损坏，fail-closed 立即终态（§4.3/EX-29）
                dispatcher.fail(candidate, false, "DESIRED_PAYLOAD_MISSING"); handled++;
            } catch (IllegalArgumentException e) {
                dispatcher.fail(candidate, false, "BAD_DESIRED_PAYLOAD"); handled++;
            } catch (Exception e) {
                log.warn("repair planner 暂败 request={}", candidate.requestId(), e);
                dispatcher.fail(candidate, true, "PLANNER_TRANSIENT"); handled++;
            }
        }
        for (var outcome : requests.findTerminalRunOutcomes(limit)) {
            if (dispatcher.projectRunOutcome(outcome.requestId())) handled++;
        }
        return handled;
    }

    public void start() { if (running.compareAndSet(false, true)) thread = Thread.ofVirtual().start(this::loop); }
    public void stop() { running.set(false); if (thread != null) thread.interrupt(); }
    private void loop() { while (running.get()) try { if (runOnce() == 0) Thread.sleep(idleSleepMs); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        catch (Exception e) { log.error("repair planner 轮询异常", e); } }
    private static final class PayloadMissingException extends RuntimeException {}
}
