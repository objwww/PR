package com.objwww.pr.publisher.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.publisher.domain.port.PayloadReader;
import com.objwww.pr.publisher.domain.port.PayloadUnavailableException;
import com.objwww.pr.shared.Digest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * CAS payload 读取器：Publisher 以只读挂载共享 Control 的 CAS 目录
 * （布局同 LocalCasArtifactStore：{@code <root>/<digest前2位>/<digest>}）。
 * 读不到/解析失败一律 fail-closed 抛 {@link PayloadUnavailableException}（E5）。
 */
public class CasPayloadReader implements PayloadReader {

    private final Path casDir;
    private final ObjectMapper objectMapper;

    public CasPayloadReader(Path casDir, ObjectMapper objectMapper) {
        this.casDir = Objects.requireNonNull(casDir);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public Map<String, Object> read(Digest payloadHash) {
        Objects.requireNonNull(payloadHash, "payloadHash");
        Path path = casDir.resolve(payloadHash.value().substring(0, 2)).resolve(payloadHash.value());
        try {
            if (!Files.isRegularFile(path)) {
                throw new PayloadUnavailableException("payload 不在 CAS: " + payloadHash);
            }
            return objectMapper.readValue(Files.readAllBytes(path), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new PayloadUnavailableException("payload 读取/解析失败: " + payloadHash, e);
        }
    }
}
