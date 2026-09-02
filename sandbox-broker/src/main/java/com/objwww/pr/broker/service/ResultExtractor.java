package com.objwww.pr.broker.service;

import com.github.dockerjava.api.DockerClient;
import com.objwww.pr.broker.client.ArtifactStorageClient;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 结果提取器（M4 §4.7 结果回传）。
 *
 * <p>职责：
 * <ul>
 *   <li>容器日志提取：docker logs → 上传到 Artifact (JOB_LOG)</li>
 *   <li>容器结果提取：archive API 提取 /out 目录 → 上传到 Artifact (JOB_RESULT)</li>
 *   <li>工具观测提取：archive API 提取 /observation → 上传到 Artifact (TOOL_OBSERVATION)</li>
 * </ul>
 */
@Service
public class ResultExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResultExtractor.class);

    private final DockerClient dockerClient;
    private final ArtifactStorageClient artifactStorageClient;

    public ResultExtractor(DockerClient dockerClient,
                           ArtifactStorageClient artifactStorageClient) {
        this.dockerClient = dockerClient;
        this.artifactStorageClient = artifactStorageClient;
    }

    /**
     * 提取容器日志并上传到 Artifact。
     *
     * @param containerId 容器 ID
     * @return 日志 digest (JOB_LOG)
     */
    public Digest extractLogs(String containerId) {
        log.debug("Extracting logs: containerId={}", containerId);

        try {
            // 使用 LogContainerResultCallback 收集日志
            StringBuilder logBuilder = new StringBuilder();
            dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .exec(new com.github.dockerjava.core.command.LogContainerResultCallback() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                        logBuilder.append(new String(frame.getPayload()));
                    }
                })
                .awaitCompletion();

            byte[] logBytes = logBuilder.toString().getBytes();
            Digest logDigest = artifactStorageClient.upload(logBytes, "JOB_LOG");
            log.info("Logs extracted: containerId={}, digest={}", containerId, logDigest.hex());
            return logDigest;

        } catch (Exception e) {
            log.error("Failed to extract logs: containerId=" + containerId, e);
            // 返回空 digest（失败不阻断流程）
            return new Digest("0".repeat(64));
        }
    }

    /**
     * 提取容器 /out 目录并上传到 Artifact。
     *
     * @param containerId 容器 ID
     * @return 结果 digest (JOB_RESULT)
     */
    public Digest extractResult(String containerId) {
        log.debug("Extracting result: containerId={}", containerId);

        try (InputStream archiveStream = dockerClient.copyArchiveFromContainerCmd(containerId, "/out")
                .exec()) {

            Digest resultDigest = artifactStorageClient.uploadStream(archiveStream, "JOB_RESULT");
            log.info("Result extracted: containerId={}, digest={}", containerId, resultDigest.hex());
            return resultDigest;

        } catch (Exception e) {
            log.warn("Failed to extract result (may not exist): containerId=" + containerId, e);
            // /out 目录可能不存在（工具未生成结果）
            return new Digest("0".repeat(64));
        }
    }

    /**
     * 提取容器 /observation 文件并上传到 Artifact。
     *
     * @param containerId 容器 ID
     * @return 观测 digest (TOOL_OBSERVATION)
     */
    public Digest extractObservation(String containerId) {
        log.debug("Extracting observation: containerId={}", containerId);

        try (InputStream archiveStream = dockerClient.copyArchiveFromContainerCmd(containerId, "/observation")
                .exec()) {

            Digest observationDigest = artifactStorageClient.uploadStream(archiveStream, "TOOL_OBSERVATION");
            log.info("Observation extracted: containerId={}, digest={}", containerId, observationDigest.hex());
            return observationDigest;

        } catch (Exception e) {
            log.warn("Failed to extract observation (may not exist): containerId=" + containerId, e);
            // /observation 文件可能不存在
            return new Digest("0".repeat(64));
        }
    }

    /**
     * 判断 digest 是否为空占位符。
     *
     * @param digest digest
     * @return true 为空占位符，false 为有效 digest
     */
    public boolean isEmptyDigest(Digest digest) {
        return digest.hex().equals("0".repeat(64));
    }
}
