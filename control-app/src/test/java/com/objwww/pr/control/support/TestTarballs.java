package com.objwww.pr.control.support;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** 程序构造 tar.gz 测试样本的工具（UT-09 / SnapshotService 测试共用） */
public final class TestTarballs {

    /** GitHub tarball 风格顶层目录前缀 */
    public static final String GH_PREFIX = "objwww-mall_R-0123456789abcdef/";

    @FunctionalInterface
    public interface TarWriter {
        void write(TarArchiveOutputStream out) throws IOException;
    }

    public static byte[] tarGz(TarWriter writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(
                new GZIPOutputStream(bytes))) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static void file(TarArchiveOutputStream out, String name, String content) throws IOException {
        file(out, name, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void file(TarArchiveOutputStream out, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        out.putArchiveEntry(entry);
        out.write(content);
        out.closeArchiveEntry();
    }

    /** 带可执行位（0755）的常规文件样本（SEC-02 纵深防御断言用） */
    public static void executableFile(TarArchiveOutputStream out, String name, String content)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(0755);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        entry.setSize(bytes.length);
        out.putArchiveEntry(entry);
        out.write(bytes);
        out.closeArchiveEntry();
    }

    public static void symlink(TarArchiveOutputStream out, String name, String target) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
        entry.setLinkName(target);
        out.putArchiveEntry(entry);
        out.closeArchiveEntry();
    }

    public static void hardlink(TarArchiveOutputStream out, String name, String target) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_LINK);
        entry.setLinkName(target);
        out.putArchiveEntry(entry);
        out.closeArchiveEntry();
    }

    public static void characterDevice(TarArchiveOutputStream out, String name) throws IOException {
        out.putArchiveEntry(new TarArchiveEntry(name, TarConstants.LF_CHR));
        out.closeArchiveEntry();
    }

    /**
     * 绝对路径样本：commons-compress 默认构造会剥离前导 '/'（preserveAbsolutePath=false），
     * 恶意样本必须显式保留才能绕过写出侧的归一化、抵达读取侧防线。
     */
    public static void absoluteFile(TarArchiveOutputStream out, String name, String content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, true);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        entry.setSize(bytes.length);
        out.putArchiveEntry(entry);
        out.write(bytes);
        out.closeArchiveEntry();
    }
}
