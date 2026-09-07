package com.objwww.pr.control.infrastructure.tool;

import com.objwww.pr.control.alert.application.tool.ToolExecutor;

import java.util.Objects;

/**
 * 固定回放工具执行器（AM4 M4-28/29）：返回构造期注入的固定响应字节——当前无冻结的
 * 实时日志/变更数据源（评审 P0-7），replay fixture 即数据面；同一 fixture 也是
 * M4-32 REPLAY_MOCK 精确匹配的回放源。纯确定性：无网络、无时钟、args 不影响输出。
 */
public class ReplayToolExecutor implements ToolExecutor {

    private final byte[] payload;

    public ReplayToolExecutor(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("回放响应不得为空");
        }
        this.payload = payload;
    }

    @Override
    public byte[] execute(ToolExecution execution) {
        Objects.requireNonNull(execution);
        return payload;
    }
}
