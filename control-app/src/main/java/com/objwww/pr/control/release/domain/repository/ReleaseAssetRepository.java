package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.shared.Digest;

import java.util.List;
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

    /**
     * 版本中心列表（EN-10，O06"实际 revision/digest 可见"）：created_at 倒序，
     * kind null = 全 kind，limit 由调用方收敛。列表只读投影，不承担完整性校验
     * （校验在消费面，如 RunbookCorpusStore 装载即验）。
     */
    List<ReleaseAsset> listRecent(String kind, int limit);
}
