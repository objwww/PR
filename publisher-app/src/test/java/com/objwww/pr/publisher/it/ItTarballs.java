package com.objwww.pr.publisher.it;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * IT 侧 tar.gz 样本构造（control-app 的 TestTarballs 在测试 jar 内不可复用，此处最小重写）。
 */
final class ItTarballs {

    /** GitHub tarball 风格顶层目录前缀（SafeTarExtractor 剥前缀后得到仓库相对路径） */
    static final String GH_PREFIX = "objwww-mall_R-0123456789abcdef/";

    @FunctionalInterface
    interface TarWriter {
        void write(TarArchiveOutputStream out) throws IOException;
    }

    static byte[] tarGz(TarWriter writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(new GZIPOutputStream(bytes))) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static void file(TarArchiveOutputStream out, String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(data.length);
        out.putArchiveEntry(entry);
        out.write(data);
        out.closeArchiveEntry();
    }

    /** 单文件快照的便捷构造：path 为仓库相对路径（自动加 GH 顶层前缀） */
    static byte[] singleFile(String repoRelativePath, String content) {
        return tarGz(out -> file(out, GH_PREFIX + repoRelativePath, content));
    }

    private ItTarballs() {
    }
}
