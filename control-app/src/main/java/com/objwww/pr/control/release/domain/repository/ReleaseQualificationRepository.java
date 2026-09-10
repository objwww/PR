package com.objwww.pr.control.release.domain.repository;

import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 发布资格仓储（EN-02，V61）：证明行 insert-only（撤销 = UPDATE 仅撤牌三列，
 * 内容列零改写；DB 面不授 delete）。{@link #findUnrevokedFor} 为资格门的查面，
 * 也是事务内重验的行源（激活事务对同批行 FOR UPDATE，与撤销串行化——P07）。
 */
public interface ReleaseQualificationRepository {

    /** 落证明行（FAIL/INCONCLUSIVE 也记录——评测结论如实入账，S09） */
    boolean insert(ReleaseQualification qualification);

    /** 候选的最新未撤销证明（任意 verdict——门按 verdict 分类报因） */
    Optional<ReleaseQualification> findUnrevokedFor(Digest candidate);

    /** 撤销：仅写 revoked_at/by/reason 三列；false = id 不存在或已撤销 */
    boolean revoke(UUID id, String by, String reason, Instant at);
}
