package com.objwww.pr.control.eval.domain.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * eval_phase_event 写面端口（EV-04；V80 建表，insert-only——一事件一行不覆盖，
 * 读面取最新事件作当前阶段，§5.1：阶段由 worker 落事件，前端不猜）。
 *
 * <p>阶段词表 = V81 前 V80 ck_eval_phase_event_phase 七值：PREPARING/INJECTING/
 * AWAITING_ALERT/AWAITING_RCA/SCORING/FINALIZING/RECOVERING。worker_id = 落事件
 * 的 worker 身份（失联判定对账锚）；detailJson = 阶段附加事实（卡因等，可空）。
 */
public interface EvalPhaseEventSink {

    /** 落一条阶段事件（insert-only；实现方不得提供 update/delete 路径） */
    void record(UUID evalRunId, String phase, Instant enteredAt,
                String workerId, String detailJson);
}
