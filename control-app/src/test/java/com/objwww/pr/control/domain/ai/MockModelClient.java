package com.objwww.pr.control.domain.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 可注入固定响应的 ModelClient 测试桩（T07 验收"mock 模型可注入"）。
 * 供 ReviewAgentLoop 等上层逻辑的确定性单测使用：响应按入队顺序消费，请求全部留痕。
 */
public final class MockModelClient implements ModelClient {

    private final Queue<ModelResult> responses = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    /** 追加一个固定响应（按调用顺序出队） */
    public MockModelClient enqueue(ModelResult result) {
        responses.add(result);
        return this;
    }

    /** 便捷：固定文本 + 零用量 + 固定模型名 */
    public MockModelClient enqueueContent(String content) {
        return enqueue(new ModelResult(content, new TokenUsage(0, 0, 0), "mock-model"));
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        requests.add(request);
        ModelResult result = responses.poll();
        if (result == null) {
            throw new IllegalStateException("MockModelClient 没有剩余响应，却被调用了第 "
                    + requests.size() + " 次");
        }
        return result;
    }

    /** 留痕的调用请求（断言 prompt/预算/超时用） */
    public List<ModelRequest> requests() {
        return List.copyOf(requests);
    }
}
