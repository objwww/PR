package com.objwww.pr.control.alert.application.tool;

import java.util.Map;

/**
 * 工具执行器契约（AM4 M4-14 注册进 ToolRegistry；M4-27/28/29 各 Agent 的真实实现）。
 * 实现 = 纯远程调用面：不做策略/预算/校验（Gateway 唯一咽喉已做），须尽量在
 * deadline 前返回（Gateway 另有硬 deadline 兜底，超时被中断）。
 * 返回原始响应字节；结果上限（resultLimit）由 Gateway 统一裁断（RESULT_OVERSIZE）。
 */
public interface ToolExecutor {

    /** 执行一次调用；抛出的异常经 Gateway 映射为错误两族 */
    byte[] execute(ToolExecution execution) throws Exception;

    /** 单次执行输入（validatedArgs 已过 ToolArgsValidator） */
    record ToolExecution(Map<String, Object> validatedArgs,
            long deadlineEpochMillis,
            long resultLimitBytes) {
    }
}
