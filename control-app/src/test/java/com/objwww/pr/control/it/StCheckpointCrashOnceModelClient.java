package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;

import com.objwww.pr.control.domain.ai.TokenUsage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ST-24 用：模型首次调用即"进程死亡"（抛 {@link StCheckpointHarness.SimulatedCrash}，
 * 模型返回前窗口），后续调用返回固定产出。调用计数即模型计数取证。
 */
final class StCheckpointCrashOnceModelClient implements ModelClient {

    private final String content;
    private final AtomicInteger calls = new AtomicInteger(0);

    StCheckpointCrashOnceModelClient(String content) {
        this.content = content;
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        if (calls.incrementAndGet() == 1) {
            throw new StCheckpointHarness.SimulatedCrash("模型返回前");
        }
        return new ModelResult(content, new TokenUsage(0, 0, 0), "mock-model");
    }

    int calls() {
        return calls.get();
    }
}
