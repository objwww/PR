package com.objwww.pr.publisher.fakes;

import com.objwww.pr.publisher.domain.port.PayloadReader;
import com.objwww.pr.publisher.domain.port.PayloadUnavailableException;
import com.objwww.pr.shared.Digest;

import java.util.HashMap;
import java.util.Map;

/** PayloadReader 内存实现：digest → 已解析字段表 */
public class FakePayloadReader implements PayloadReader {

    private final Map<String, Map<String, Object>> payloads = new HashMap<>();

    public void put(Digest digest, Map<String, Object> payload) {
        payloads.put(digest.value(), payload);
    }

    @Override
    public Map<String, Object> read(Digest payloadHash) {
        Map<String, Object> payload = payloads.get(payloadHash.value());
        if (payload == null) {
            throw new PayloadUnavailableException("fake: payload 不存在 " + payloadHash);
        }
        return payload;
    }
}
