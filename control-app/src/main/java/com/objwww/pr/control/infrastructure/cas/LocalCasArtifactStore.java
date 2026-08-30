package com.objwww.pr.control.infrastructure.cas;

import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.shared.Digest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 本地文件系统 CAS：{@code <casDir>/<digest前2位>/<digest>}。
 * 写放大避免半截文件：先写同目录临时文件再原子 rename。
 * 目录走配置项 {@code app.artifact.cas-dir}（默认 ./var/cas），代码不写死。
 *
 * <p>刻意不加 Spring 注解：默认 profile 空跑不装配，接线属后续任务。
 */
public class LocalCasArtifactStore implements ArtifactStore {

    private final Path casDir;

    public LocalCasArtifactStore(Path casDir) {
        this.casDir = Objects.requireNonNull(casDir);
    }

    @Override
    public String putIfAbsent(Digest digest, byte[] content) {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(content, "content");
        Path target = pathOf(digest);
        try {
            if (Files.exists(target)) {
                return storagePath(digest); // 内容寻址：同 digest 即同内容，幂等
            }
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), digest.value(), ".tmp");
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                // 部分文件系统不支持原子 rename，退化为普通 move（target 不存在时才发生）
                Files.move(tmp, target);
            }
            // CAS 文件不可变且跨容器共享（publisher 以不同 uid 只读挂载同一卷，B16/T18）：
            // createTempFile 默认 600，publisher 会 AccessDenied → PAYLOAD_UNAVAILABLE。
            // 落成 444：摘掉含 owner 在内的所有写位，跨 uid 可读（POSIX 语义）
            try {
                Files.setPosixFilePermissions(target, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException nonPosix) {
                // 非 POSIX 平台（本机 Windows 开发/单测）：无权限位概念，跳过
            }
            return storagePath(digest);
        } catch (IOException e) {
            throw new UncheckedIOException("CAS 落盘失败: " + target, e);
        }
    }

    @Override
    public boolean exists(Digest digest) {
        return Files.exists(pathOf(digest));
    }

    @Override
    public Optional<byte[]> get(Digest digest) {
        Path target = pathOf(Objects.requireNonNull(digest));
        try {
            return Files.exists(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("CAS 读取失败: " + target, e);
        }
    }

    /** 登记进 artifact.storage_path 的相对标识（相对 casDir，便于整体迁移 CAS 目录） */
    private String storagePath(Digest digest) {
        return digest.value().substring(0, 2) + "/" + digest.value();
    }

    private Path pathOf(Digest digest) {
        return casDir.resolve(digest.value().substring(0, 2)).resolve(digest.value());
    }
}
