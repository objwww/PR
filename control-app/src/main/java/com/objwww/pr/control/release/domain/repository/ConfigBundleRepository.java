package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Optional;

/**
 * ConfigBundle 仓储（M5-09）：bundle 行 immutable（DB 面 select,insert——V24），
 * active pointer 单行 CAS。历史 bundle 行永不被改写；回滚 = pointer 指回（INV-AM5-5）。
 */
public interface ConfigBundleRepository {

    /** 下一 revision（发布面单调递增） */
    long nextRevision();

    /** 插入 bundle 行；false = 同 digest 已存在（发布幂等重放锚） */
    boolean insert(ConfigBundle bundle);

    Optional<ConfigBundle> findByDigest(Digest digest);

    /** 当前激活 digest；空 = 从未激活 */
    Optional<Digest> activeDigest();

    /** 指针视图（digest/revision/activatedAt 三件，GET /active 响应面） */
    record ActivePointer(Digest bundleDigest, long revision, Instant activatedAt) {
    }

    Optional<ActivePointer> findActivePointer();

    /**
     * 单事务原子激活（无半激活态）：pointer 行 CAS——仅当当前值恰为 {@code expectedCurrent}
     * （null = 未激活态）时落位。false = 并发竞争败者（调用方 409）。
     */
    boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at);
}
