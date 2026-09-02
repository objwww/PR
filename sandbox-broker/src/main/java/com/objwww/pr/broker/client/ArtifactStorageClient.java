package com.objwww.pr.broker.client;

import com.objwww.pr.shared.Digest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Artifact 存储客户端（Broker → Artifact 存储，物料提取 + 结果上传）。
 *
 * <p>核心接口：
 * <ul>
 *   <li>download：根据 digest 下载 artifact 内容（tar.gz 格式）</li>
 *   <li>upload：上传新 artifact，返回 digest</li>
 * </ul>
 */
@Component
public class ArtifactStorageClient {

    private final RestTemplate restTemplate;
    private final String artifactBaseUrl;

    public ArtifactStorageClient(String controlBaseUrl) {
        this.artifactBaseUrl = controlBaseUrl + "/api/artifacts";
        this.restTemplate = new RestTemplate();
    }

    /**
     * 下载 artifact 内容。
     *
     * @param digest artifact digest
     * @return artifact 内容字节数组
     */
    public byte[] download(Digest digest) {
        String url = artifactBaseUrl + "/" + digest.hex() + "/content";

        try {
            return restTemplate.getForObject(url, byte[].class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to download artifact: " + digest.hex(), e);
        }
    }

    /**
     * 上传 artifact 内容。
     *
     * @param content artifact 内容字节数组
     * @param artifactType artifact 类型（TOOL_OBSERVATION/JOB_LOG/JOB_RESULT）
     * @return 上传后的 digest
     */
    public Digest upload(byte[] content, String artifactType) {
        String url = artifactBaseUrl + "/upload?type=" + artifactType;

        try {
            UploadResponse response = restTemplate.postForObject(url, content, UploadResponse.class);
            if (response == null || response.digest == null) {
                throw new RuntimeException("Upload failed: no digest returned");
            }
            return new Digest(response.digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload artifact", e);
        }
    }

    /**
     * 上传 InputStream 内容。
     *
     * @param inputStream 内容输入流
     * @param artifactType artifact 类型
     * @return 上传后的 digest
     */
    public Digest uploadStream(InputStream inputStream, String artifactType) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int nRead;
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return upload(buffer.toByteArray(), artifactType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload from stream", e);
        }
    }

    public static class UploadResponse {
        public String digest;
    }
}
