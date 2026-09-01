package com.objwww.pr.control.it;

import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.shared.Digest;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ST-25/ST-26 用：CAS 写路径崩溃注入（仿 publisher 侧 CrashyPublicationStore 形态）。
 * {@link #armFailOnPut(int)} 之后第 N 次 {@link #putIfAbsent} 抛
 * {@link StCheckpointHarness.SimulatedCrash}（一次性，之后恢复委托）：
 * <ul>
 *   <li>N=2 → ST-25a 窗口：findings blob 已落盘、model response blob 未写即崩；</li>
 *   <li>读路径不注入：续跑读回的是崩溃留下的真实残局。</li>
 * </ul>
 * 种子阶段先把输入 blob 装好再 arm（arm 前调用不计数）。
 */
final class StCheckpointCrashArtifactStore implements ArtifactStore {

    private final ArtifactStore delegate;
    private final AtomicInteger armedPuts = new AtomicInteger(-1);
    private final AtomicInteger seenPuts = new AtomicInteger(0);

    StCheckpointCrashArtifactStore(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    /** 武装：此后第 failOnPut 次 putIfAbsent 抛崩溃（1 起计） */
    void armFailOnPut(int failOnPut) {
        seenPuts.set(0);
        armedPuts.set(failOnPut);
    }

    @Override
    public String putIfAbsent(Digest digest, byte[] content) {
        int n = seenPuts.incrementAndGet();
        if (armedPuts.get() == n) {
            armedPuts.set(-1); // 只炸一次：恢复后路径必须能成功
            throw new StCheckpointHarness.SimulatedCrash("CAS 第 " + n + " 次 putIfAbsent 中途");
        }
        return delegate.putIfAbsent(digest, content);
    }

    @Override
    public boolean exists(Digest digest) {
        return delegate.exists(digest);
    }

    @Override
    public Optional<byte[]> get(Digest digest) {
        return delegate.get(digest);
    }
}
