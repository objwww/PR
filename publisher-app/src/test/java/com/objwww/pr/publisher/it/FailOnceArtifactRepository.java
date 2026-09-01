package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.repository.ArtifactRepository;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Optional;
import com.objwww.pr.shared.Digest;

/**
 * EX-07 用：T2 事务中途爆炸的 artifact 登记桩——第一次登记 REVIEW_PAYLOAD 时抛异常
 * （模拟 Control 在 T2 提交前崩溃/依赖故障），之后恢复委托。
 */
final class FailOnceArtifactRepository implements ArtifactRepository {

    private final ArtifactRepository delegate;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    FailOnceArtifactRepository(ArtifactRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public void register(ArtifactRecord record) {
        if (armed.get() && record.artifactType() == ArtifactType.REVIEW_PAYLOAD) {
            armed.set(false); // 只炸一次：重放路径必须能成功
            throw new IllegalStateException("模拟 Control 于 T2 提交前崩溃（EX-07）");
        }
        delegate.register(record);
    }

    @Override
    public Optional<ArtifactRecord> findByDigest(Digest digest) {
        return delegate.findByDigest(digest);
    }
}
