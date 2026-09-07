package com.objwww.pr.control.infrastructure.archive;

import com.objwww.pr.control.ops.domain.repository.ColdArchiveStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * 本地盘卷冷层（M5-19；冷层形态开放项 O-5 的当前裁定面——本地盘卷先落，
 * 对象存储实现随部署段冻结；ref = file:// 绝对路径，回读即证据面）。
 */
public class LocalColdArchiveStore implements ColdArchiveStore {

    private static final String REF_SCHEME = "file://";

    private final Path root;

    public LocalColdArchiveStore(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    @Override
    public String export(String partition, byte[] content) {
        try {
            Path dir = root.resolve(partition);
            Files.createDirectories(dir);
            Path file = dir.resolve(UUID.randomUUID() + ".dump");
            Files.write(file, content);
            return REF_SCHEME + file;
        } catch (IOException e) {
            throw new IllegalStateException("冷层写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] readBack(String ref) {
        if (ref == null || !ref.startsWith(REF_SCHEME)) {
            throw new IllegalArgumentException("非法冷层引用");
        }
        Path file = Path.of(ref.substring(REF_SCHEME.length())).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("冷层引用越界");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("冷层回读失败: " + e.getMessage(), e);
        }
    }
}
