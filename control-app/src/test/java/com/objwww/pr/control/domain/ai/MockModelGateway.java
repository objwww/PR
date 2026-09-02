package com.objwww.pr.control.domain.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * 测试用 Mock Gateway（§2 实现纪律 2）：保留 MockModelClient 语义，
 * 适配 ModelGatewayPort 接口。响应按入队顺序消费，请求全部留痕。
 */
public class MockModelGateway implements ModelGatewayPort {

    private final Queue<ModelResult> responses = new ArrayDeque<>();
    private final Queue<RuntimeException> exceptionQueue = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    /** 追加一个固定响应（按调用顺序出队） */
    public MockModelGateway enqueue(ModelResult result) {
        responses.add(result);
        return this;
    }

    /** 便捷：固定文本 + 零用量 + 固定模型名 */
    public MockModelGateway enqueueContent(String content) {
        return enqueue(new ModelResult(content, new TokenUsage(0, 0, 0), "mock-model"));
    }

    /** 追加一个固定异常（按调用顺序抛出，优先于响应队列） */
    public MockModelGateway enqueueException(RuntimeException ex) {
        exceptionQueue.offer(ex);
        return this;
    }

    @Override
    public RoutedModelResult complete(ModelRequest request, ModelCallContext context) {
        requests.add(request);
        if (!exceptionQueue.isEmpty()) {
            throw exceptionQueue.poll();
        }
        ModelResult result = responses.poll();
        if (result == null) {
            throw new IllegalStateException("MockModelGateway 没有剩余响应，却被调用了第 "
                    + requests.size() + " 次");
        }
        return routed(result);
    }

    /** 留痕的调用请求（断言 prompt/调用次数用） */
    public List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    /** mock 路由/身份的 RoutedModelResult 包装（it 包崩溃/阻塞桩同形复用） */
    public static RoutedModelResult routed(ModelResult result) {
        return new RoutedModelResult(
                result,
                new ModelRoute("mock-route", "mock-model", "mock-endpoint",
                        "mock-quota", "mock-credential", null),
                new ModelRouteIdentity("mock-provider", "mock-model", "v1"),
                UUID.randomUUID(), 1, null, false, Duration.ofMillis(100));
    }
}
