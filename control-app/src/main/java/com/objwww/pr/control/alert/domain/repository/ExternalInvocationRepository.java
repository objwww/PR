package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.ExternalInvocation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * external_invocation_ledger 端口（V5 形态；只 INSERT+SELECT+终态列级 UPDATE）。
 *
 * <p>SQL 契约：insertStarted 独立短事务在触网前落 STARTED 行——<b>写失败=零触网</b>（§6.5）；
 * finish 走列级 UPDATE（state/response_digest/http_status/…/finished_at）；
 * 崩溃回收把悬挂 STARTED 标 UNKNOWN（CT-A08）。
 */
public interface ExternalInvocationRepository {

    /** 调用前 STARTED 行（requestDigest 不含密钥）；失败抛异常=调用方不得触网 */
    void insertStarted(ExternalInvocation invocation);

    /** 终态回写（SUCCEEDED/FAILED/UNKNOWN） */
    boolean finish(ExternalInvocation invocation);

    /** 悬挂 STARTED（started_at < olderThan）→ 崩溃回收扫描 */
    List<ExternalInvocation> findHangingStarted(Instant olderThan);

    List<ExternalInvocation> findByRunId(UUID runId);
}
