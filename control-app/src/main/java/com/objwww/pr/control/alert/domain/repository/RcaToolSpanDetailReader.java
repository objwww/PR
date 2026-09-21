package com.objwww.pr.control.alert.domain.repository;

import java.util.List;
import java.util.UUID;

/**
 * run 级工具 span 明细读面（M-d T2 Trace 六要素补齐）：工具参数面/返回摘要/证据引用。
 *
 * <p>数据源 = 既有账本关联（rca_tool_invocation LEFT JOIN rca_evidence via result_ref），
 * 零新采集、零前端造数。与 {@link RcaRunTraceReader} 的关系：瀑布 span 行（三层统一
 * 投影）保持 18 字段不变（RunQueryService/既有测试零扰动）；本读面按 invocation id
 * 补挂工具维三要素，前端瀑布展开行经 span.id 关联后展示。
 *
 * <p>边界纪律：完整工具参数原文与全量返回体维持既有消毒面（事件流白名单）不放开；
 * 本读面只下发 scope 参数面（截断 300 字）与返回体摘要（截断 500 字）——Trace 六要素
 * 的"参数/返回摘要"是摘要语义，不是原文镜像。
 */
public interface RcaToolSpanDetailReader {

    /** 某 run 全部工具调用明细（started_at 升序；无行 → 空表如实，不造数） */
    List<ToolSpanDetail> detailsByRun(UUID runId);

    /**
     * 工具 span 明细行：invocationId = span.id 关联键；
     * scopeSummary = 证据信封 scope（查询范围参数面，canonical JSON 截断 300 字）；
     * resultSummary = 证据 payload（工具返回体 canonical JSON 截断 500 字）；
     * evidenceRef/evidenceType/evidenceSource = 证据引用（result_ref 为 null 时三值
     * 全 null——调用未产出证据，如 VALIDATE_ONLY 受理或失败调用，如实不造数）；
     * reasonDetail = BA-190 拒因具体消息（V155 reason_detail；null = 无详情/旧行，
     * 与瀑布 span 的 errorCode=reason_code 配套，前端按 invocationId 关联展示）。
     */
    record ToolSpanDetail(UUID invocationId, UUID taskId, String toolName, long callSeq,
                          String scopeSummary, String resultSummary,
                          String evidenceRef, String evidenceType, String evidenceSource,
                          String reasonDetail) {
    }
}
