package com.objwww.pr.control.domain.port;

import com.objwww.pr.shared.Digest;

import java.util.Optional;

/**
 * 内容寻址存储端口（CAS）：key 即内容 digest，同 digest 重复写入是幂等空操作。
 * 大对象（快照、diff、finding 正文、模型响应）只进 CAS，库里只存 digest（v2.2 §5）。
 */
public interface ArtifactStore {

    /**
     * 按 digest 落盘（已存在则直接返回，不覆写）。
     *
     * @return 存储路径（相对标识，登记进 artifact.storage_path）
     */
    String putIfAbsent(Digest digest, byte[] content);

    /** digest 是否已存在于 CAS */
    boolean exists(Digest digest);

    /** 按 digest 读回内容（WorkItemWorker 执行 Step 时装 input）；不存在返回空 */
    Optional<byte[]> get(Digest digest);
}
