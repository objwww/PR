package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaToolCall;

import java.util.List;
import java.util.UUID;

/**
 * rca_tool_call 接口（M3-07；只 INSERT + SELECT）。
 *
 * <p>SQL 契约：insertAll 与 result 同栅栏——observed_generation 与 rca_run.generation
 * 不一致的整批拒绝（0 行），不落半批；PK (investigation_result_id, tool_call_id) 去重。
 */
public interface RcaToolCallRepository {

    /** 栅栏批量插入（一批一事务语义）；返回成功写入条数（栅栏拒绝 = 0） */
    int insertAll(List<RcaToolCall> toolCalls);

    List<RcaToolCall> findByResultId(UUID investigationResultId);

    List<RcaToolCall> findByRunId(UUID runId);
}
