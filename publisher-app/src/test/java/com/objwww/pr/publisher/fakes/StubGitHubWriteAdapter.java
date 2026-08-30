package com.objwww.pr.publisher.fakes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.publisher.infrastructure.github.GitHubTransportException;
import com.objwww.pr.publisher.infrastructure.github.GitHubWriteAdapter;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * GitHubWriteAdapter 测试桩：整体覆写 execute/executeRead，不触网。
 * 预置响应队列；传输故障经 {@link #failTransport} 注入。
 */
public class StubGitHubWriteAdapter extends GitHubWriteAdapter {

    public final List<TypedWriteRequest> writeRequests = new ArrayList<>();
    public final List<TypedReadRequest> readRequests = new ArrayList<>();

    private final Deque<TypedResponse> writeResponses = new ArrayDeque<>();
    private final Deque<TypedResponse> readResponses = new ArrayDeque<>();
    private boolean failTransport = false;

    public StubGitHubWriteAdapter() {
        super(null, "http://stub.invalid", new ObjectMapper(), Duration.ofSeconds(5));
    }

    public void respondWrite(TypedResponse response) {
        writeResponses.add(response);
    }

    public void respondRead(TypedResponse response) {
        readResponses.add(response);
    }

    public void failTransport(boolean fail) {
        this.failTransport = fail;
    }

    @Override
    public TypedResponse execute(TypedWriteRequest request) {
        writeRequests.add(request);
        if (failTransport) {
            throw new GitHubTransportException("stub: 模拟响应丢失", new java.io.IOException("boom"));
        }
        return writeResponses.isEmpty() ? TypedResponse.ofStatus(500) : writeResponses.poll();
    }

    @Override
    public TypedResponse executeRead(TypedReadRequest request) {
        readRequests.add(request);
        if (failTransport) {
            throw new GitHubTransportException("stub: 模拟响应丢失", new java.io.IOException("boom"));
        }
        return readResponses.isEmpty() ? TypedResponse.ofStatus(404) : readResponses.poll();
    }
}
