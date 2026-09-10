package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.shared.Digest;

import java.util.Optional;

/**
 * 发布资产仓储（EN-01，V60）：(kind, digest) 唯一、行 immutable（DB 面
 * select,insert，同 V24 惯例）。digest 内容寻址 = 注册幂等锚 + 篡改新身份锚（S10）。
 */
public interface ReleaseAssetRepository {

    /** 插入资产行；false = (kind,digest) 已存在（注册幂等重放锚） */
    boolean insert(ReleaseAsset asset);

    /** 按 kind+digest 精确解析（依赖闭包校验的查面；kind 不同同 digest = 不同资产） */
    Optional<ReleaseAsset> findByDigest(String kind, Digest digest);
}
