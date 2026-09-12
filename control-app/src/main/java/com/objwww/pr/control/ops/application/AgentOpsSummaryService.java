package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.repository.AgentOpsReader;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.AgentOpsAggregate;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.ToolCallCount;
import com.objwww.pr.control.ops.domain.repository.AgentOpsReader.WorkerActivity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * UI-6 监控大盘聚合服务（/api/agent-ops/summary 面；SQL 聚合归 {@link AgentOpsReader}，
 * 本类只钉 generatedAt 并直透端口口径——诚实 null（oldestReadyWaitSeconds/tokens24h）
 * 不回填 0，沿 IncidentQueryService 同律）。
 */
public class AgentOpsSummaryService {

    private final AgentOpsReader reader;
    private final Supplier<Instant> now;

    public AgentOpsSummaryService(AgentOpsReader reader, Supplier<Instant> now) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.now = Objects.requireNonNull(now, "now");
    }

    /** 大盘聚合应答（record 字段名即 JSON 契约） */
    public record AgentOpsSummaryResponse(long activeRuns, long awaitingReviewRuns,
                                          long readyTasks, Long oldestReadyWaitSeconds,
                                          long openCases, long notifyOutboxPending,
                                          long notifyOutboxFailed24h, long llmCalls24h,
                                          Long tokens24h, List<ToolCallCount> topTools24h,
                                          Instant generatedAt) {
    }

    public AgentOpsSummaryResponse summary() {
        Instant at = now.get();
        AgentOpsAggregate aggregate = reader.summary(at);
        return new AgentOpsSummaryResponse(aggregate.activeRuns(),
                aggregate.awaitingReviewRuns(), aggregate.readyTasks(),
                aggregate.oldestReadyWaitSeconds(), aggregate.openCases(),
                aggregate.notifyOutboxPending(), aggregate.notifyOutboxFailed24h(),
                aggregate.llmCalls24h(), aggregate.tokens24h(), aggregate.topTools24h(), at);
    }

    /**
     * 执行器活性应答（监控页「执行器」区，方案 §三.12；record 字段名即 JSON 契约）。
     * derivedFromLeaseActivity 恒 true——显式声明「按租约活动推导，非心跳注册表」，
     * 前端文案依据；asOf 必带（新鲜度判断归前端）。
     */
    public record WorkerActivityResponse(List<WorkerActivity> workers, long windowMinutes,
                                         boolean derivedFromLeaseActivity, Instant asOf) {
    }

    public WorkerActivityResponse workers(Duration window) {
        Instant at = now.get();
        return new WorkerActivityResponse(reader.workerActivity(at, window),
                window.toMinutes(), true, at);
    }
}
