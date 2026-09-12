package com.objwww.pr.control.release.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Skill 候选（EN-08，增强线方案 §四 生命周期契约）：自动生成器仅能写 DRAFT；
 * VALIDATING/EVALUATING 失败保留 REJECTED 记录（INCONCLUSIVE 属评测结论，不能
 * 晋升）；QUALIFIED 门槛 = 存在未撤销且 PASS 的评测证明（ReleaseQualification，
 * MATCHED 只对账不背书 S09）；ACTIVE = 人工授权发布（激活人+时刻必在场）。
 *
 * <p>状态机封闭集（本类型唯一推进权威）：{@code DRAFT → VALIDATING →
 * EVALUATING → QUALIFIED → ACTIVE → (DEPRECATED)? → RETIRED}，任意失败态 =
 * REJECTED；ACTIVE 可紧急直退 RETIRED（S12 紧急停止与普通退场语义分开）。
 * 其余边一律 {@link IllegalStateException}。行永不删——历史可恢复（S12）。
 *
 * <p>零框架（L0：release 域零框架规则）。asset_digest 指向 release_asset SKILL
 * 行（内容寻址）：REJECTED 行必无指针（DB CHECK 同钉）；篡改产生新 digest，
 * 本指针不动（S10 激活时重算对账）。
 */
public record SkillCandidate(UUID id,
                             String name,
                             UUID sourceRunId,
                             String sourceDigest,
                             String verificationStatus,
                             String assetDigest,
                             String status,
                             String failureReason,
                             String proposedBy,
                             String activatedBy,
                             Instant activatedAt,
                             String retiredBy,
                             Instant retiredAt,
                             String retireReason,
                             Instant createdAt,
                             Instant updatedAt) {

    public static final String VERIFIED = "VERIFIED";
    public static final String UNVERIFIED = "UNVERIFIED";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_VALIDATING = "VALIDATING";
    public static final String ST_EVALUATING = "EVALUATING";
    public static final String ST_QUALIFIED = "QUALIFIED";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DEPRECATED = "DEPRECATED";
    public static final String ST_RETIRED = "RETIRED";
    public static final String ST_REJECTED = "REJECTED";

    static final Set<String> STATUSES = Set.of(ST_DRAFT, ST_VALIDATING, ST_EVALUATING,
            ST_QUALIFIED, ST_ACTIVE, ST_DEPRECATED, ST_RETIRED, ST_REJECTED);
    static final Set<String> VERIFICATIONS = Set.of(VERIFIED, UNVERIFIED);

    private static final Pattern SOURCE_DIGEST = Pattern.compile("[0-9a-f]{64}");

    public SkillCandidate {
        Objects.requireNonNull(id, "id 不得为 null");
        requireNonBlank(name, "name");
        Objects.requireNonNull(sourceRunId, "sourceRunId 不得为 null（原料必溯源封存轨迹）");
        if (sourceDigest == null || !SOURCE_DIGEST.matcher(sourceDigest).matches()) {
            throw new IllegalArgumentException(
                    "source_digest 必须为 64 位小写 hex（封存轨迹冻结面）: " + sourceDigest);
        }
        if (verificationStatus == null || !VERIFICATIONS.contains(verificationStatus)) {
            throw new IllegalArgumentException(
                    "verification_status 必须为 VERIFIED/UNVERIFIED: " + verificationStatus);
        }
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 必须在封闭集内: " + status);
        }
        requireNonBlank(proposedBy, "proposed_by");
        if (ST_REJECTED.equals(status) && UNVERIFIED.equals(verificationStatus)
                && assetDigest != null) {
            throw new IllegalArgumentException(
                    "UNVERIFIED 原料的 REJECTED 候选不得携带资产指针（S02 隔离面）");
        }
        if (ST_ACTIVE.equals(status)
                && (activatedBy == null || activatedBy.isBlank() || activatedAt == null)) {
            throw new IllegalArgumentException("ACTIVE 必带人工授权面（activated_by/at）");
        }
        if (ST_RETIRED.equals(status) && (retiredBy == null || retiredBy.isBlank()
                || retiredAt == null || retireReason == null || retireReason.isBlank())) {
            throw new IllegalArgumentException("RETIRED 必带退场三件套（by/at/reason，S12）");
        }
    }

    // ------------------------------------------------------------ 转移面（唯一推进权威）

    private SkillCandidate require(String expected) {
        if (!expected.equals(status)) {
            throw new IllegalStateException(
                    "Skill 候选状态机违规：期望 " + expected + " 实际 " + status
                            + "（候选 " + name + "/" + id + "）");
        }
        return this;
    }

    private static void requireTransition(String from, String to) {
        Set<String> allowed = switch (from) {
            case ST_DRAFT -> Set.of(ST_VALIDATING, ST_REJECTED);
            case ST_VALIDATING -> Set.of(ST_EVALUATING, ST_REJECTED);
            case ST_EVALUATING -> Set.of(ST_QUALIFIED, ST_REJECTED);
            case ST_QUALIFIED -> Set.of(ST_ACTIVE);
            case ST_ACTIVE -> Set.of(ST_DEPRECATED, ST_RETIRED);
            case ST_DEPRECATED -> Set.of(ST_RETIRED);
            default -> Set.of();
        };
        if (!allowed.contains(to)) {
            throw new IllegalStateException(
                    "Skill 候选状态机违规：无边 " + from + " → " + to
                            + "（候选生命周期封闭集，S12）");
        }
    }

    /**
     * 带新字段的一次性转移构造（先验边、后构造终态行——避免"先建中间态再改字段"
     * 二次构造触发终态不变量校验）。
     */
    private SkillCandidate edge(String to, String failureReason, String activatedBy,
            java.time.Instant activatedAt, String retiredBy,
            java.time.Instant retiredAt, String retireReason, Instant now) {
        requireTransition(status, to);
        return new SkillCandidate(id, name, sourceRunId, sourceDigest,
                verificationStatus, assetDigest, to, failureReason,
                proposedBy, activatedBy, activatedAt, retiredBy, retiredAt,
                retireReason, createdAt, now);
    }

    /** DRAFT→VALIDATING（校验启动） */
    public SkillCandidate startValidation(Instant now) {
        return edge(ST_VALIDATING, failureReason, activatedBy, activatedAt,
                retiredBy, retiredAt, retireReason, now);
    }

    /** VALIDATING→EVALUATING（静态校验过） */
    public SkillCandidate passValidation(Instant now) {
        return edge(ST_EVALUATING, failureReason, activatedBy, activatedAt,
                retiredBy, retiredAt, retireReason, now);
    }

    /** →REJECTED（失败必留痕；已落 DRAFT 资产者保指针——历史不改写只前进） */
    public SkillCandidate reject(String reason, Instant now) {
        Objects.requireNonNull(reason, "reason 不得为 null（失败必留痕）");
        return edge(ST_REJECTED, reason, activatedBy, activatedAt,
                retiredBy, retiredAt, retireReason, now);
    }

    /** 携带 DRAFT 资产指针（propose 落资产后）；仅 DRAFT 可装填 */
    public SkillCandidate withAsset(String digest, Instant now) {
        require(ST_DRAFT);
        if (digest == null || !SOURCE_DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("asset_digest 必须为 64 位小写 hex: " + digest);
        }
        return new SkillCandidate(id, name, sourceRunId, sourceDigest,
                verificationStatus, digest, status, failureReason, proposedBy,
                activatedBy, activatedAt, retiredBy, retiredAt, retireReason,
                createdAt, now);
    }

    /** EVALUATING→QUALIFIED：调用方须先确认 ReleaseQualification.passQualified()（S09 门） */
    public SkillCandidate qualify(Instant now) {
        return edge(ST_QUALIFIED, failureReason, activatedBy, activatedAt,
                retiredBy, retiredAt, retireReason, now);
    }

    /** QUALIFIED→ACTIVE（人工授权发布；by 必为真人操作者标识，S08 评测身份不得激活） */
    public SkillCandidate activate(String by, Instant now) {
        requireNonBlank(by, "activated_by（人工授权面）");
        return edge(ST_ACTIVE, failureReason, by, now, retiredBy, retiredAt,
                retireReason, now);
    }

    /** ACTIVE→DEPRECATED（普通退场第一步）；ACTIVE→RETIRED（紧急直退）归 {@link #retire} */
    public SkillCandidate deprecate(Instant now) {
        return edge(ST_DEPRECATED, failureReason, activatedBy, activatedAt,
                retiredBy, retiredAt, retireReason, now);
    }

    /** ACTIVE/DEPRECATED→RETIRED（退场三件套必在场；紧急撤销=ACTIVE 直退本面，S12） */
    public SkillCandidate retire(String by, String reason, Instant now) {
        requireNonBlank(by, "retired_by");
        requireNonBlank(reason, "retire_reason");
        return edge(ST_RETIRED, failureReason, activatedBy, activatedAt,
                by, now, reason, now);
    }

    public boolean isActive() {
        return ST_ACTIVE.equals(status);
    }

    /** 快照投影（接口/日志面；不含内部审计字段全量） */
    public Map<String, Object> brief() {
        return Map.of("id", id.toString(), "name", name, "status", status,
                "asset_digest", assetDigest == null ? "" : assetDigest);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
    }
}
