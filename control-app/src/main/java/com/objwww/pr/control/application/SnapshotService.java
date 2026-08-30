package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.port.GitHubSourcePort;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.control.domain.snapshot.SafeTarExtractor;
import com.objwww.pr.control.domain.snapshot.SnapshotTree;
import com.objwww.pr.shared.Digest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * T0 快照准备（application 编排，事务外运行——网络 I/O 不进 DB 事务，评审修正 #3）：
 * 按不可变 SHA 取 tarball（只 archive 不 checkout，B-6）→ 安全解包（UT-09 防线）
 * → 算 source_snapshot_digest → CAS 落盘 → artifact 登记；diff 同样 digest + CAS。
 *
 * <p>返回的 digest 是 T1 insert PRRevision 的前置（I12：insert 时 digest 必须已就绪）。
 * 安全解包拒绝（SecurityRejectionException）直接向上抛，由调用方把 Step 记 FAILED_TERMINAL
 * 并落安全事件（EX-10），不降级进入评审流程。
 *
 * <p>刻意不加 Spring 注解：接线属后续任务，默认 profile 空跑不装配。
 */
public class SnapshotService {

    private final GitHubSourcePort source;
    private final SafeTarExtractor extractor;
    private final ArtifactStore artifactStore;
    private final ArtifactRepository artifactRepository;

    public SnapshotService(GitHubSourcePort source, SafeTarExtractor extractor,
                           ArtifactStore artifactStore, ArtifactRepository artifactRepository) {
        this.source = Objects.requireNonNull(source);
        this.extractor = Objects.requireNonNull(extractor);
        this.artifactStore = Objects.requireNonNull(artifactStore);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
    }

    /**
     * 准备 head 快照与 base..head diff，返回两者 digest。
     * CAS 内已存在的 digest 直接复用（同 SHA 重放不重复下载内容落盘）。
     */
    public SnapshotOutcome prepare(long installationId, String repoFullName,
                                   String baseSha, String headSha) {
        // 1) 源码快照：tarball → 安全解包 → digest → CAS
        byte[] tarball = source.fetchTarball(installationId, repoFullName, headSha);
        SnapshotTree tree = extractor.extract(tarball);
        Digest snapshotDigest = tree.digest();
        String snapshotPath = artifactStore.putIfAbsent(snapshotDigest, tarball);
        artifactRepository.register(new ArtifactRecord(snapshotDigest, ArtifactType.SOURCE_SNAPSHOT,
                tarball.length, snapshotPath, Instant.now()));

        // 2) diff 文本：digest = sha256(diff 全文)，同走 CAS
        String diff = source.fetchDiff(installationId, repoFullName, baseSha, headSha);
        byte[] diffBytes = diff.getBytes(StandardCharsets.UTF_8);
        Digest diffDigest = Digest.sha256Of(diff);
        String diffPath = artifactStore.putIfAbsent(diffDigest, diffBytes);
        artifactRepository.register(new ArtifactRecord(diffDigest, ArtifactType.DIFF_BUNDLE,
                diffBytes.length, diffPath, Instant.now()));

        return new SnapshotOutcome(snapshotDigest, diffDigest, tree.fileCount(), tree.totalBytes());
    }

    /** T0 产出：供 T1 建 PRRevision 的两个 digest + 快照统计（落账 payload 用） */
    public record SnapshotOutcome(Digest sourceSnapshotDigest, Digest diffDigest,
                                  int fileCount, long totalBytes) {
    }
}
