package com.objwww.pr.shared.snapshot;

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
 *   <li>symlink / hardlink 允许存在但不收录内容（目标逃出根时抛 ESCAPED_LINK）；</li>
 *   <li>设备文件 / FIFO 等一律拒绝（SPECIAL_FILE）；</li>
 *   <li>单文件解压大小超 {@code maxFileSizeBytes} → FILE_TOO_LARGE；</li>
 *   <li>文件数超 {@code maxFileCount} → TOO_MANY_FILES；</li>
 *   <li>总解压大小超 {@code maxTotalSizeBytes} → TOTAL_SIZE_EXCEEDED。</li>
 * </ul>
 */
public final class SafeTarExtractor {

    private final int maxFileSizeBytes;
    private final int maxFileCount;
    private final long maxTotalSizeBytes;

    /**
     * @param maxFileSizeBytes   单文件解压后大小上限（防畸形大文件）
     * @param maxFileCount       文件数上限（防条目数爆炸）
     * @param maxTotalSizeBytes  总解压大小上限（防压缩炸弹）
     */
    public SafeTarExtractor(int maxFileSizeBytes, int maxFileCount, long maxTotalSizeBytes) {
        if (maxFileSizeBytes <= 0 || maxFileCount <= 0 || maxTotalSizeBytes <= 0) {
            throw new IllegalArgumentException("limits must be > 0");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFileCount = maxFileCount;
        this.maxTotalSizeBytes = maxTotalSizeBytes;
    }

    /**
     * 从 gzip 压缩的 tar 归档中安全提取所有常规文件，构造 {@link SnapshotTree}。
     * symlink 仅校验目标不逃逸，不收录内容（M0 静态评审无需跟随链接，B-6 archive-only）。
     *
     * @param tarGz 完整的 .tar.gz 字节
     * @return 解包后的内存快照（路径剥离顶层前缀、按字典序排序、带整体 digest）
     * @throws IOException                 解压/读取失败
     * @throws SecurityRejectionException  违反任一安全规则
     */
    public SnapshotTree extractSnapshot(byte[] tarGz) throws IOException {
        Objects.requireNonNull(tarGz, "tarGz");
        byte[] tar = gunzip(tarGz);
        List<SnapshotTree.Entry> regularFiles = new ArrayList<>();
        List<String> allPaths = new ArrayList<>();
        long totalBytes = 0;

        try (TarArchiveInputStream tis = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                String name = entry.getName();
                allPaths.add(name);

                // 路径合规性检查（所有条目类型都走一遍）
                validatePath(name, entry);

                if (entry.isDirectory()) {
                    continue; // 目录不收录
                }

                if (entry.isSymbolicLink() || entry.isLink()) {
                    // symlink/hardlink：校验目标不逃逸，但不收录内容
                    validateLink(name, entry.getLinkName(), allPaths);
                    continue;
                }

                // EX-78: 显式拒绝设备文件和 FIFO（安全防线，不能靠 isFile() 隐式覆盖）
                if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.SPECIAL_FILE, name,
                            "设备/FIFO 条目");
                }

                if (!entry.isFile()) {
                    // 其他特殊类型一律拒绝
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.SPECIAL_FILE, name,
                            "不支持的条目类型");
                }

                // 常规文件：检查大小上限并读取内容
                long size = entry.getSize();
                if (size < 0) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.FILE_TOO_LARGE, name,
                            "无效的 size: " + size);
                }

                // 先检查总大小（压缩炸弹防线优先，与旧版一致）
                totalBytes += size;
                if (totalBytes > maxTotalSizeBytes) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.TOTAL_SIZE_EXCEEDED, name,
                            "总解压大小 " + totalBytes + " B 超限 " + maxTotalSizeBytes);
                }

                // 再检查单文件大小
                if (size > maxFileSizeBytes) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.FILE_TOO_LARGE, name,
                            "单文件 " + size + " B 超限 " + maxFileSizeBytes);
                }

                if (regularFiles.size() >= maxFileCount) {
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.TOO_MANY_FILES, name,
                            "文件数超限 " + maxFileCount);
                }

                byte[] content = tis.readNBytes((int) size);
                if (content.length != size) {
                    throw new IOException("文件 " + name + " 读取不完整: " + content.length + " / " + size);
                }
                regularFiles.add(new SnapshotTree.Entry(name, content));
            }
        }

        // 剥离顶层公共前缀（GitHub tarball 形态：foo-abc123/src/Main.java → src/Main.java）
        List<SnapshotTree.Entry> normalized = stripCommonPrefix(regularFiles);
        return SnapshotTree.of(normalized);
    }

    /** 路径合规性检查（所有条目类型都走一遍） */
    private void validatePath(String name, TarArchiveEntry entry) {
        // 拒绝绝对路径
        if (name.startsWith("/") || (name.length() >= 2 && name.charAt(1) == ':')) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.ABSOLUTE_PATH, name,
                    "绝对路径条目");
        }

        // 拒绝含 '\' 的名字（跨平台歧义）
        if (name.indexOf('\\') >= 0) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.PATH_TRAVERSAL, name,
                    "含反斜杠（跨平台分隔符歧义）");
        }

        // 拒绝任何含 ".." 段的路径（宁严勿宽）
        String[] segments = name.split("/");
        for (String seg : segments) {
            if ("..".equals(seg)) {
                throw new SecurityRejectionException(
                        SecurityRejectionException.Reason.PATH_TRAVERSAL, name,
                        "含 '..' 段");
            }
        }
    }

    /** symlink/hardlink 目标逃逸检查（相对解析后不得逃出解包根） */
    private void validateLink(String linkPath, String target, List<String> allPaths) {
        if (target == null || target.isEmpty()) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.ESCAPED_LINK, linkPath,
                    "链接目标为空");
        }

        // 拒绝绝对目标
        if (target.startsWith("/") || (target.length() >= 2 && target.charAt(1) == ':')) {
            throw new SecurityRejectionException(
                    SecurityRejectionException.Reason.ESCAPED_LINK, linkPath,
                    "链接目标为绝对路径: " + target);
        }

        // 模拟相对解析：从 linkPath 父目录出发，逐段 cd target
        Deque<String> stack = new ArrayDeque<>();
        String parent = linkPath.contains("/") ? linkPath.substring(0, linkPath.lastIndexOf('/')) : "";
        if (!parent.isEmpty()) {
            for (String seg : parent.split("/")) {
                if (!seg.isEmpty() && !".".equals(seg)) {
                    stack.push(seg);
                }
            }
        }

        for (String seg : target.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (stack.isEmpty()) {
                    // 向上越过根目录 → 逃逸
                    throw new SecurityRejectionException(
                            SecurityRejectionException.Reason.ESCAPED_LINK, linkPath,
                            "链接目标逃逸: " + target);
                }
                stack.pop();
            } else {
                stack.push(seg);
            }
        }
        // 解析成功且未逃逸（symlink 本身不收录内容，校验通过即可）
    }

    /** gzip 解压（包装 checked exception） */
    private byte[] gunzip(byte[] gzBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** 所有条目共享同一顶层目录（GitHub tarball 形态）时剥离该前缀；否则原样保留 */
    private List<SnapshotTree.Entry> stripCommonPrefix(List<SnapshotTree.Entry> raws) {
        if (raws.isEmpty()) {
            return raws;
        }
        String first = raws.get(0).path();
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
