package com.objwww.pr.control.domain.snapshot;

import com.objwww.pr.shared.Digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 安全解包后的内存快照：只收常规文件（路径已剥离顶层前缀、按字典序排序）。
 * symlink 仅校验不收录（M0 静态阅读不需要跟随链接，B-6 archive-only）。
 *
 * <p>source_snapshot_digest 规则（自定但确定，UT-09 要求同输入同 digest）：
 * 按路径字典序，逐条向 SHA-256 链追加 {@code path + 0x00 + sha256hex(content) + '\n'}。
 */
public record SnapshotTree(List<Entry> entries, Digest digest, long totalBytes) {

    /** 单个常规文件条目（path 为相对 POSIX 路径，content 为完整内容） */
    public record Entry(String path, byte[] content) {
        public Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(content, "content");
        }
    }

    public SnapshotTree {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(digest, "digest");
    }

    /** 由条目清单构造（内部按路径字典序排序定序），digest 在此处一次性算定 */
    public static SnapshotTree of(List<Entry> entries) {
        List<Entry> sorted = entries.stream()
                .sorted(java.util.Comparator.comparing(Entry::path))
                .toList();
        return new SnapshotTree(sorted, computeDigest(sorted),
                sorted.stream().mapToLong(e -> e.content().length).sum());
    }

    public int fileCount() {
        return entries.size();
    }

    /** 规范化清单 + 内容的 SHA-256 链（规则见类注释） */
    static Digest computeDigest(List<Entry> sortedEntries) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Entry e : sortedEntries) {
                md.update(e.path().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                MessageDigest contentMd = MessageDigest.getInstance("SHA-256");
                md.update(HexFormat.of().formatHex(contentMd.digest(e.content()))
                        .getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return new Digest(HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
