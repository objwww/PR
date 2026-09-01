package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.ai.TokenUsage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ST-29 用：首个模型调用挂起的双闩客户端——{@link #awaitEntered()} 保证旧 Worker
 * 已领到租约并卡在模型调用内（"执行中"窗口），测试主线程随后拨过期租约让新 Worker
 * 接管；{@link #release()} 放行后旧 Worker 的晚到写必须被租约栅栏拦下。
 */
final class StCheckpointBlockingModelClient implements ModelClient {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger(0);
    private final String content;

    StCheckpointBlockingModelClient(String content) {
        this.content = content;
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        calls.incrementAndGet();
        entered.countDown();
        try {
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new StCheckpointHarness.SimulatedCrash("阻塞模型等待放行超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StCheckpointHarness.SimulatedCrash("阻塞模型被中断");
        }
        return new ModelResult(content, new TokenUsage(0, 0, 0), "mock-model");
    }

    /** 等旧 Worker 进入模型调用（= 已领租约、执行中） */
    void awaitEntered() throws InterruptedException {
        if (!entered.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("旧 Worker 未在 60s 内进入模型调用");
        }
    }

    void release() {
        release.countDown();
    }

    int calls() {
        return calls.get();
    }
}
