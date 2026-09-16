package com.objwww.pr.control.alert.domain.repository;

import java.util.List;
import java.util.UUID;

/**
 * run 级调用链瀑布读面（前端产品化 3.17 Trace 瀑布页签）：任务尝试/模型调用/工具调用
 * 三层 span 统一投影。数据源 = 既有三张账本（rca_attempt / rca_model_call /
 * rca_tool_invocation），零新采集、零前端造数。
 *
 * <p>span 之间无外键父子语义（模型/工具行经 task_id 挂到任务），瀑布分层由前端
 * 渲染承担；本端口只如实按账本行给数。
 */
public interface RcaRunTraceReader {

    /** 某 run 全部 span 行（start 升序；无行 → 空表如实，不造数） */
    List<SpanRow> spansByRun(UUID runId);

    /**
     * 统一 span 投影（UNION ALL 直出；kind ∈ task/model/tool，字段按 kind 部分为 null）：
     * label = task_key / role_id / tool_name；seq = attempt_no / action_seq / call_seq；
     * start/end = 账本起止（模型行 end 恒 null——诚实终点 = created_at + latencyMs）；
     * errorCode = attempt.error_code / model.error_code / tool.reason_code（脱敏原因码）。
     */
    record SpanRow(String kind, UUID id, UUID taskId, String label, long seq,
                   String state, String start, String end, Long latencyMs,
                   String model, Integer promptTokens, Integer completionTokens,
                   Integer totalTokens, Long costMicros, String errorCode,
                   String worker, Integer attemptNo, String roleId) {
    }
}
