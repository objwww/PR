package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.model.ArtifactType;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.control.domain.port.GitHubSourcePort;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import com.objwww.pr.shared.snapshot.SafeTarExtractor;
import com.objwww.pr.shared.snapshot.SecurityRejectionException;
import com.objwww.pr.control.infrastructure.cas.LocalCasArtifactStore;
import com.objwww.pr.control.support.TestTarballs;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SnapshotService（T0 编排）单元测试：假 GitHubSourcePort + 真实 CAS（临时目录）+
 * 内存 ArtifactRepository，验证 digest 可复算、CAS 布局、artifact 登记与恶意样本拦截。
 */
class SnapshotServiceTest {

    @TempDir
    Path casDir;

    private static final long INSTALLATION_ID = 880001L;
    private static final String REPO = "objwww/mall_R";
    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String HEAD = "2222222222222222222222222222222222222222";
    private static final String DIFF = "diff --git a/a.txt b/a.txt\n+hello\n";

    /** 内存假端口：固定返回程序构造的 tarball 与 diff，记录调用 */
    private static final class FakeGitHubSource implements GitHubSourcePort {
        private final byte[] tarball;
        int tarballCalls;

        FakeGitHubSource(byte[] tarball) {
            this.tarball = tarball;
        }

        @Override
        public byte[] fetchTarball(long installationId, String repoFullName, String sha) {
            tarballCalls++;
            return tarball;
        }

        @Override
        public String fetchDiff(long installationId, String repoFullName, String baseSha, String headSha) {
            return DIFF;
        }
    }

    /** 内存假登记库：按 digest 去重，模拟 ON CONFLICT DO NOTHING */
    private static final class InMemoryArtifactRepository implements ArtifactRepository {
        private final List<ArtifactRecord> records = new ArrayList<>();

        @Override
        public void register(ArtifactRecord record) {
            records.removeIf(r -> r.digest().equals(record.digest()));
            records.add(record);
        }

        @Override
        public java.util.Optional<ArtifactRecord> findByDigest(Digest digest) {
            return records.stream().filter(r -> r.digest().equals(digest)).findFirst();
        }
    }

    private byte[] normalTarball() {
        return TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "README.md", "# mall_R");
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "src/Main.java", "class Main {}");
        });
    }

    @Test
    void prepareComputesDigestStoresCasAndRegistersArtifacts() throws Exception {
        byte[] tarball = normalTarball();
        FakeGitHubSource source = new FakeGitHubSource(tarball);
        InMemoryArtifactRepository repo = new InMemoryArtifactRepository();
        SnapshotService service = new SnapshotService(
                source, new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024), new LocalCasArtifactStore(casDir), repo);

        SnapshotService.SnapshotOutcome outcome = service.prepare(INSTALLATION_ID, REPO, BASE, HEAD);

        // digest 可复算：独立再解包一次，结果一致
        Digest recomputed = new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024).extractSnapshot(tarball).digest();
        assertEquals(recomputed, outcome.sourceSnapshotDigest());
        assertEquals(Digest.sha256Of(DIFF), outcome.diffDigest());
        assertEquals(2, outcome.fileCount());

        // CAS 布局：cas/<digest前2位>/<digest>，内容为原始 tarball 字节
        Path snapshotFile = casDir.resolve(outcome.sourceSnapshotDigest().value().substring(0, 2))
                .resolve(outcome.sourceSnapshotDigest().value());
        assertTrue(Files.exists(snapshotFile));
        org.junit.jupiter.api.Assertions.assertArrayEquals(tarball, Files.readAllBytes(snapshotFile));

        // artifact 登记：SOURCE_SNAPSHOT + DIFF_BUNDLE 各一条
        assertEquals(2, repo.records.size());
        assertEquals(ArtifactType.SOURCE_SNAPSHOT, repo.records.get(0).artifactType());
        assertEquals(ArtifactType.DIFF_BUNDLE, repo.records.get(1).artifactType());
        assertEquals(tarball.length, repo.records.get(0).sizeBytes());
    }

    @Test
    void prepareIsIdempotentOnCasWhenReplayingSameSha() {
        byte[] tarball = normalTarball();
        FakeGitHubSource source = new FakeGitHubSource(tarball);
        InMemoryArtifactRepository repo = new InMemoryArtifactRepository();
        ArtifactStore store = new LocalCasArtifactStore(casDir);
        SnapshotService service = new SnapshotService(source, new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024), store, repo);

        SnapshotService.SnapshotOutcome first = service.prepare(INSTALLATION_ID, REPO, BASE, HEAD);
        SnapshotService.SnapshotOutcome second = service.prepare(INSTALLATION_ID, REPO, BASE, HEAD);

        assertEquals(first.sourceSnapshotDigest(), second.sourceSnapshotDigest());
        assertTrue(store.exists(first.sourceSnapshotDigest()));
        assertTrue(store.exists(first.diffDigest()));
        assertEquals(2, repo.records.size()); // 同 digest 重复登记仍为两条
    }

    @Test
    void maliciousTarballIsRejectedBeforeAnyCasWrite() {
        byte[] evil = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "../../etc/passwd", "root"));
        FakeGitHubSource source = new FakeGitHubSource(evil);
        InMemoryArtifactRepository repo = new InMemoryArtifactRepository();
        SnapshotService service = new SnapshotService(
                source, new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024), new LocalCasArtifactStore(casDir), repo);

        // EX-10 路径：安全解包拒绝直接上抛，CAS 与登记表零污染
        assertThrows(SecurityRejectionException.class,
                () -> service.prepare(INSTALLATION_ID, REPO, BASE, HEAD));
        assertTrue(repo.records.isEmpty());
        assertEquals(0, casDir.toFile().list().length);
    }
}
