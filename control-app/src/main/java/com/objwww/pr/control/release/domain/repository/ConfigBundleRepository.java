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
     * 单事务原子激活（EN-02 资格化，无半激活态）：expectedActiveRevision = 客户端
     * 预期的当前激活 revision（0 = 未激活态约定）——服务端不替调用方推算预期，
     * 0 行 = 预期陈旧（并发竞争败者，调用方 409）。false = 竞争败者或预期不匹配。
     */
    boolean activateQualified(Digest toDigest, long expectedActiveRevision, String by,
            Instant at);

    /**
     * 激活/回滚事实（EX-B1）：与 pointer CAS 同事务落 change_event——配置生效与变更
     * 证据同生死（评审 B1：控制器事后写事件失败 = 生效但证据缺失）。CAS 败者事务内
     * 零插入；幂等重放在服务层早退，不触本方法。rollbackOf 仅 ROLLBACK 行携带
     * （回滚前生效 digest），ACTIVATE 为 null。
     *
     * <p>EN-02：实现必须在<b>同一事务</b>内重验目标资格未撤销（与撤销行锁串行化，
     * P07"事务内重验拒绝陈旧资格"）——无有效 PASS 资格即零移动零事实。
     */
    record ActivationFact(String action, String service, String environment, Digest rollbackOf) {
    }

    /** 带 ChangeFact 记档的资格化激活；事实面不强制 */
    default boolean activateQualified(Digest toDigest, long expectedActiveRevision,
            String by, Instant at, ActivationFact fact) {
        return activateQualified(toDigest, expectedActiveRevision, by, at);
    }
}
