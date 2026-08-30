package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.TokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * IT 侧模型桩（control-app 的 MockModelClient 在测试 jar 内不可复用，最小重写）：
 * 响应按入队顺序出队；也可入队异常（模型超时/预算场景，EX-06）。
 */
final class ItModelClient implements ModelClient {

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
    public ModelResult complete(ModelRequest request) {
        requests.add(request);
        Object next = scripted.poll();
        if (next == null) {
            throw new IllegalStateException("ItModelClient 没有剩余编排，却被调用了第 " + requests.size() + " 次");
        }
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        return (ModelResult) next;
    }

    List<ModelRequest> requests() {
        return List.copyOf(requests);
    }
}
