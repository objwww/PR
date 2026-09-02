package com.objwww.pr.broker.service;

import com.objwww.pr.broker.client.ArtifactStorageClient;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.snapshot.SafeTarExtractor;
import com.objwww.pr.shared.snapshot.SecurityRejectionException;
import com.objwww.pr.shared.snapshot.SnapshotTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Workspace 管理器（M4 §4.6 物料提取）。
 *
 * <p>职责：
 * <ul>
 *   <li>从 Artifact 存储下载 workspace tar.gz</li>
 *   <li>SafeTarExtractor 安全解包（UT-09/EX-10/B-6 防线）</li>
 *   <li>写入临时目录，供容器挂载</li>
 * </ul>
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final ArtifactStorageClient artifactStorageClient;
    private final SafeTarExtractor safeTarExtractor;

    // SafeTarExtractor 限额（与 control-app 一致）
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;      // 10 MB
    private static final int MAX_FILE_COUNT = 10000;                 // 1 万文件
    private static final long MAX_TOTAL_SIZE = 100 * 1024 * 1024L;  // 100 MB

    public WorkspaceManager(ArtifactStorageClient artifactStorageClient) {
        this.artifactStorageClient = artifactStorageClient;
        this.safeTarExtractor = new SafeTarExtractor(MAX_FILE_SIZE, MAX_FILE_COUNT, MAX_TOTAL_SIZE);
    }

    /**
     * 提取 workspace 物料到临时目录。
     *
     * @param sourceSnapshotDigest 源码快照 digest
     * @param inputDigests 其他输入 artifact digest 列表
     * @return workspace 临时目录路径
     * @throws SecurityRejectionException 安全解包拒绝
     * @throws IOException 解包失败
     */
    public Path extractWorkspace(Digest sourceSnapshotDigest, List<Digest> inputDigests)
            throws SecurityRejectionException, IOException {
        log.info("Extracting workspace: sourceSnapshot={}, inputs={}",
                 sourceSnapshotDigest.hex(), inputDigests.size());

        // 1. 创建临时目录
        Path workspaceDir = Files.createTempDirectory("sandbox-workspace-");
        log.debug("Created workspace dir: {}", workspaceDir);

        try {
            // 2. 下载并解包源码快照
            byte[] snapshotTarGz = artifactStorageClient.download(sourceSnapshotDigest);
            SnapshotTree snapshot = safeTarExtractor.extractSnapshot(snapshotTarGz);
            log.info("Extracted snapshot: {} files, {} bytes",
                     snapshot.fileCount(), snapshot.totalBytes());

            // 3. 写入文件到 workspace
            for (SnapshotTree.Entry entry : snapshot.entries()) {
                Path filePath = workspaceDir.resolve(entry.path());
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, entry.content());
            }

            // 4. 下载并解包其他输入 artifacts（diff bundle / policy 等）
            for (Digest inputDigest : inputDigests) {
                byte[] inputTarGz = artifactStorageClient.download(inputDigest);
                SnapshotTree inputSnapshot = safeTarExtractor.extractSnapshot(inputTarGz);
                log.debug("Extracted input artifact: digest={}, files={}",
                          inputDigest.hex(), inputSnapshot.fileCount());

                // 写入到 workspace（可能覆盖同名文件）
                for (SnapshotTree.Entry entry : inputSnapshot.entries()) {
                    Path filePath = workspaceDir.resolve(entry.path());
                    Files.createDirectories(filePath.getParent());
                    Files.write(filePath, entry.content());
                }
            }

            log.info("Workspace extracted successfully: {}", workspaceDir);
            return workspaceDir;

        } catch (SecurityRejectionException | IOException e) {
            // 解包失败：清理临时目录
            cleanupWorkspace(workspaceDir);
            throw e;
        }
    }

    /**
     * 清理 workspace 临时目录。
     *
     * @param workspaceDir workspace 目录路径
     */
    public void cleanupWorkspace(Path workspaceDir) {
        if (workspaceDir == null) {
            return;
        }

        try {
            log.debug("Cleaning up workspace: {}", workspaceDir);
            deleteRecursively(workspaceDir);
        } catch (IOException e) {
            log.warn("Failed to cleanup workspace: " + workspaceDir, e);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.list(path).forEach(child -> {
                try {
                    deleteRecursively(child);
                } catch (IOException e) {
                    log.warn("Failed to delete: " + child, e);
                }
            });
        }
        Files.deleteIfExists(path);
    }
}
