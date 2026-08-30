package com.objwww.pr.shared;

/**
 * GitHub 操作封闭枚举（AFT-04：TypedWriteRequest/TypedReadRequest 只允许携带操作枚举，
 * 不存在 raw url/method 字段；枚举值 → 具体 HTTP 动词与路径的映射只在 GitHubWriteAdapter 内）。
 */
public enum GitHubOperation {

    /** 写：POST /repos/{repo}/check-runs（external_id = operation_id） */
    CREATE_CHECK_RUN,
    /** 写：PATCH /repos/{repo}/check-runs/{check_run_id} */
    UPDATE_CHECK_RUN,
    /** 写：POST /repos/{repo}/pulls/{pr}/reviews（commit_id 绑 head_sha，body 含隐藏 marker） */
    CREATE_REVIEW,
    /** 读（reconcile 探测）：GET /repos/{repo}/check-runs/{check_run_id} */
    GET_CHECK_RUN,
    /** 读（reconcile 探测）：GET /repos/{repo}/commits/{sha}/check-runs，分页 */
    LIST_CHECKS_FOR_SHA,
    /** 读（reconcile 探测）：GET /repos/{repo}/pulls/{pr}/reviews，分页 */
    LIST_REVIEWS;

    /** 是否写操作（TypedWriteRequest 只接受写操作，TypedReadRequest 反之） */
    public boolean isWrite() {
        return this == CREATE_CHECK_RUN || this == UPDATE_CHECK_RUN || this == CREATE_REVIEW;
    }
}
