package com.objwww.pr.publisher.infrastructure.selfcheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

/**
 * 文件安全探针（T15 可测试抽象）：私钥只读挂载断言（B16）的文件系统视图。
 * POSIX 权限在非 POSIX 平台（Windows 开发机）返回空，由判定逻辑回退到
 * {@link Files#isWritable} 检查。
 */
public interface FileSecurityProbe {

    boolean exists(Path path);

    /** POSIX 权限集；平台不支持 POSIX 时返回空（调用方走 writable 回退） */
    Optional<Set<PosixFilePermission>> posixPermissions(Path path);

    boolean writable(Path path);

    static FileSecurityProbe system() {
        return new FileSecurityProbe() {
            @Override
            public boolean exists(Path path) {
                return Files.exists(path);
            }

            @Override
            public Optional<Set<PosixFilePermission>> posixPermissions(Path path) {
                try {
                    return Optional.of(Files.getPosixFilePermissions(path));
                } catch (UnsupportedOperationException e) {
                    return Optional.empty(); // 非 POSIX 平台（Windows 开发机）
                } catch (IOException e) {
                    throw new UncheckedIOException("读取文件权限失败: " + path, e);
                }
            }

            @Override
            public boolean writable(Path path) {
                return Files.isWritable(path);
            }
        };
    }
}
