package com.objwww.pr.control.domain.snapshot;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * tar.gz 安全解包器（纯逻辑，零框架；UT-09 / EX-10 / B-6 archive-only 在 Control 侧的防线）。
 *
 * <p>硬性规则（任一违反即抛 {@link SecurityRejectionException}，不跳过、不降级）：
 * <ul>
 *   <li>拒绝绝对路径（"/..." 或 Windows 盘符）；</li>
 *   <li>拒绝任何含 ".." 段的路径（不做"恰好没穿越"的宽纵判断）；</li>
 *   <li>拒绝含 '\' 的名字（跨平台分隔符歧义，宁严勿宽）；</li>
 *   <li>symlink / hardlink 目标解析后必须仍在解包根内，链接本身不收录进快照；</li>
 *   <li>拒绝设备 / FIFO 等一切非常规文件条目；</li>
 *   <li>限制单文件大小、总解压大小（压缩炸弹防线）、文件数。</li>
 * </ul>
 *
 * <p>GitHub tarball 所有条目共享顶层目录前缀 {@code <owner>-<repo>-<sha>/}，
 * 解包后统一剥离该公共前缀，得到仓库相对路径。
 */
public final class SafeTarExtractor {

    public static final long DEFAULT_MAX_TOTAL_BYTES = 100L * 1024 * 1024; // 100MB
    public static final int DEFAULT_MAX_FILES = 50_000;
    public static final long DEFAULT_MAX_FILE_BYTES = 20L * 1024 * 1024;   // 20MB

    private static final int READ_CHUNK = 64 * 1024;

    private final long maxTotalBytes;
    private final int maxFiles;
    private final long maxFileBytes;

    public SafeTarExtractor() {
        this(DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_FILES, DEFAULT_MAX_FILE_BYTES);
    }

    /** 测试用小限额构造；生产用默认限额 */
    public SafeTarExtractor(long maxTotalBytes, int maxFiles, long maxFileBytes) {
        this.maxTotalBytes = maxTotalBytes;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
    }

    /** 解包并返回规范化快照（条目按路径字典序排序，digest 已算定） */
    public SnapshotTree extract(byte[] tarGz) {
        Objects.requireNonNull(tarGz, "tarGz");
        List<SnapshotTree.Entry> raws = new ArrayList<>();
        long totalBytes = 0;
        try (TarArchiveInputStream in =
                     new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(tarGz)))) {
            TarArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                checkName(name);
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.SPECIAL_FILE, name, "设备/FIFO 条目");
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    checkLinkTarget(name, entry.getLinkName());
                    continue; // 链接仅校验不收录（B-6 静态阅读，不跟随）
                }
                if (!entry.isFile()) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.SPECIAL_FILE, name, "未知类型条目");
                }
                if (raws.size() + 1 > maxFiles) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.TOO_MANY_FILES, name, "文件数超过上限 " + maxFiles);
                }
                byte[] content = readLimited(in, name);
                totalBytes += content.length;
                if (totalBytes > maxTotalBytes) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.TOTAL_SIZE_EXCEEDED, name,
                            "总解压大小超过上限 " + maxTotalBytes + " 字节（压缩炸弹防线）");
                }
                raws.add(new SnapshotTree.Entry(name, content));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("tarball 损坏或不可解析", e);
        }
        return SnapshotTree.of(stripCommonPrefix(raws));
    }

    /** 绝对路径与 ".." 穿越检查（对剥离前缀前的原始名字做，防前缀剥离掩盖穿越） */
    private void checkName(String name) {
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.ABSOLUTE_PATH, name, "绝对路径条目");
        }
        if (name.contains("\\")) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.PATH_TRAVERSAL, name, "含反斜杠的名字（跨平台分隔符歧义）");
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..")) {
                throw new SecurityRejectionException(
                        SecurityRejectionException.Reason.PATH_TRAVERSAL, name, "含 '..' 段");
            }
        }
    }

    /**
     * 链接目标检查：目标解析（相对条目所在目录）后必须仍在解包根内。
     * 用段栈归一化：遇 ".." 弹栈，栈空还要弹即逃逸。
     */
    private void checkLinkTarget(String entryName, String linkName) {
        if (linkName == null || linkName.isEmpty()
                || linkName.startsWith("/") || linkName.startsWith("\\")
                || linkName.matches("^[A-Za-z]:.*")) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.ESCAPED_LINK, entryName, "链接目标为绝对路径: " + linkName);
        }
        String parent = entryName.contains("/")
                ? entryName.substring(0, entryName.lastIndexOf('/')) : "";
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : (parent.isEmpty() ? linkName : parent + "/" + linkName).split("/")) {
            switch (segment) {
                case "", "." -> { /* 跳过 */ }
                case ".." -> {
                    if (stack.pollLast() == null) {
                        throw new SecurityRejectionException(
                                SecurityRejectionException.Reason.ESCAPED_LINK, entryName,
                                "链接目标逃逸解包根: " + linkName);
                    }
                }
                default -> stack.addLast(segment);
            }
        }
    }

    /** 按实际上限读单文件内容（不信 entry.getSize()，防线按真实读到的字节计） */
    private byte[] readLimited(TarArchiveInputStream in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK];
        int n;
        long size = 0;
        while ((n = in.read(chunk)) != -1) {
            size += n;
            if (size > maxFileBytes) {
                throw new SecurityRejectionException(
                        SecurityRejectionException.Reason.FILE_TOO_LARGE, name,
                        "单文件解压后超过上限 " + maxFileBytes + " 字节");
            }
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    /** 所有条目共享同一顶层目录（GitHub tarball 形态）时剥离该前缀；否则原样保留 */
    private List<SnapshotTree.Entry> stripCommonPrefix(List<SnapshotTree.Entry> raws) {
        if (raws.isEmpty()) {
            return raws;
        }
        String first = raws.getFirst().path();
        int slash = first.indexOf('/');
        if (slash < 0) {
            return raws; // 无目录结构，无可剥离
        }
        String prefix = first.substring(0, slash + 1);
        for (SnapshotTree.Entry e : raws) {
            if (!e.path().startsWith(prefix) || e.path().length() == prefix.length()) {
                return raws; // 不共享顶层目录，保持原名
            }
        }
        return raws.stream()
                .map(e -> new SnapshotTree.Entry(e.path().substring(prefix.length()), e.content()))
                .toList();
    }
}
