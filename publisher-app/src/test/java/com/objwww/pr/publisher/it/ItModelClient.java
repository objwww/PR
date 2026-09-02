package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.ai.ModelCallContext;
import com.objwww.pr.control.domain.ai.ModelGatewayPort;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.ModelRoute;
import com.objwww.pr.control.domain.ai.ModelRouteIdentity;
import com.objwww.pr.control.domain.ai.RoutedModelResult;
import com.objwww.pr.control.domain.ai.TokenUsage;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * IT 侧模型桩（control-app 的 MockModelGateway 在测试 jar 内不可复用，最小重写）：
 * 响应按入队顺序出队；也可入队异常（模型超时/预算场景，EX-06）。
 *
 * <p>M3：实现 ModelGatewayPort（原 ModelClient 已删除）。契约身份固定 it/mock-model/v1，
 * 与 ItHarness 注入 executor 的 ModelRouteCatalog 一致（checkpoint 复用不断链）。
 */
final class ItModelClient implements ModelGatewayPort {

    /** checkpoint 契约身份（与 ItHarness 的 catalog lambda 同值） */
    static final ModelRouteIdentity IDENTITY = new ModelRouteIdentity("it", "mock-model", "v1");

    private final Queue<Object> scripted = new ArrayDeque<>(); // ModelResult 或 RuntimeException
    private final List<ModelRequest> requests = new ArrayList<>();

    ItModelClient enqueueContent(String content) {
        scripted.add(new ModelResult(content, new TokenUsage(0, 0, 0), "it-model"));
        return this;
    }

    ItModelClient enqueue(ModelResult result) {
        scripted.add(result);
        return this;
    }

    ItModelClient enqueueFailure(RuntimeException failure) {
        scripted.add(failure);
        return this;
    }

    @Override
    public RoutedModelResult complete(ModelRequest request, ModelCallContext context) {
        requests.add(request);
        Object next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("ItModelClient 没有剩余编排，却被调用了第 " + requests.size() + " 次");
        }
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        ModelResult result = (ModelResult) next;
        return new RoutedModelResult(result,
                new ModelRoute("it-route", "mock-model", "it-endpoint", "it-quota", "it-cred", null),
                IDENTITY, UUID.randomUUID(), 1, null, false, Duration.ofMillis(1));
    }

    List<ModelRequest> requests() {
        return List.copyOf(requests);
    }
}
