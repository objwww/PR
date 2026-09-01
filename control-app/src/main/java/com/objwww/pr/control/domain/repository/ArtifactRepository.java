package com.objwww.pr.control.domain.repository;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.shared.Digest;

import java.util.Optional;

/**
 * artifact 登记表端口（domain 接口；infrastructure 用 JdbcClient 实现）。
 * digest 为主键：register 必须幂等（同 digest 重复登记是空操作）。
 */
public interface ArtifactRepository {

    void register(ArtifactRecord record);

    Optional<ArtifactRecord> findByDigest(Digest digest);
}
