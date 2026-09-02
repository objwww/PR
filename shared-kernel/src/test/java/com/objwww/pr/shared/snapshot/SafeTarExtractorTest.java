package com.objwww.pr.shared.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SafeTarExtractor 单元测试（M4-A 验证）。
 */
class SafeTarExtractorTest {

    private SafeTarExtractor extractor;

    @BeforeEach
    void setUp() {
        // 使用默认限制：10000 文件，100MB 单文件，1GB 总大小
        extractor = new SafeTarExtractor(10000, 100 * 1024 * 1024, 1024L * 1024 * 1024);
    }

    @Test
    void testExtractSnapshot_emptyTarGz() throws IOException {
        // 创建空 tar.gz
        byte[] emptyTarGz = createEmptyTarGz();

        SnapshotTree tree = extractor.extractSnapshot(emptyTarGz);

        assertNotNull(tree);
        assertEquals(0, tree.fileCount());
        assertEquals(0, tree.totalBytes());
        assertNotNull(tree.digest());
    }

    @Test
    void testExtractSnapshot_nullInput() {
        assertThrows(NullPointerException.class, () -> {
            extractor.extractSnapshot(null);
        });
    }

    @Test
    void testConstructor_invalidLimits() {
        // maxFiles <= 0
        assertThrows(IllegalArgumentException.class, () -> {
            new SafeTarExtractor(0, 100 * 1024 * 1024, 1024L * 1024 * 1024);
        });

        // maxFileSize <= 0
        assertThrows(IllegalArgumentException.class, () -> {
            new SafeTarExtractor(10000, 0, 1024L * 1024 * 1024);
        });

        // maxTotalSize <= 0
        assertThrows(IllegalArgumentException.class, () -> {
            new SafeTarExtractor(10000, 100 * 1024 * 1024, 0);
        });
    }

    /**
     * 创建空的 tar.gz（只有 EOF 块）。
     */
    private byte[] createEmptyTarGz() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            // 写入两个 512 字节的零块（tar EOF 标记）
            byte[] eofBlock = new byte[1024];
            gzos.write(eofBlock);
        }
        return baos.toByteArray();
    }
}
