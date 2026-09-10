package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseManifest;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ConfigBundle 应用服务（M5-09；EN-01/02 扩展）：Git 编辑源 → 发布（canonical
 * digest 幂等落库）→ <b>单事务原子激活</b>（事务边界在 PostgresConfigBundleRepository
 * 的 pointer CAS，无半激活态）→ 回滚 = pointer 指回旧 digest，不改历史行（INV-AM5-5）。
 * 决策可回溯：每次激活/回滚记录 bundle digest/revision/操作者（审计日志行 +
 * config_bundle_active.activated_by 落档）。
 *
 * <p>EN-01：发布组合携带 release_manifest 段时，发布面做<b>依赖闭包校验</b>——
 * 段内引用的 Prompt/Skill/工具 schema 资产必须已注册（P04：缺失即拒绝，零落库，
 * 指针天然不动）；资产经 {@link #registerAsset} 内容寻址注册（幂等）。
 *
 * <p>EN-02 发布资格门：activate/rollback <b>统一</b>过资格门——目标必须存在未撤销
 * 且 quality_verdict=PASS 的评测证明（S09/P07/P11）；调用方必须携带 expected
 * active revision（0 = 未激活约定），服务端不替用户推算预期（P06，陈旧 → 409 面）。
 * 门拒绝以 {@link IllegalStateException} 抛出，消息前缀 QUALIFICATION_* 为原因码。
 *
 * <p>沿 GoldenCandidateService 惯例：本类不加 @Service（装配归 PersistenceConfig，
 * docker profile 唯一装配点）；未装配 Postgres 仓储 bean 前测试直构。
 */
public class ConfigBundleService {

    private static final Logger log = LoggerFactory.getLogger(ConfigBundleService.class);

    /** EX-B1 变更事实固定面：变更对象 = 本控制面自身，环境 = 生产 */
    static final String CHANGE_SERVICE = "control-app";
    static final String CHANGE_ENVIRONMENT = "production";

    /** 未激活态的预期 revision 约定（pointer 种子行 bundle_digest IS NULL） */
    public static final long EXPECTED_REVISION_NEVER_ACTIVATED = 0L;

    private final ConfigBundleRepository repository;
    private final ReleaseAssetRepository assets;
    private final ReleaseQualificationRepository qualifications;

    public ConfigBundleService(ConfigBundleRepository repository,
            ReleaseAssetRepository assets, ReleaseQualificationRepository qualifications) {
        this.repository = Objects.requireNonNull(repository, "repository 不得为 null");
        this.assets = Objects.requireNonNull(assets, "assets 不得为 null");
        this.qualifications =
                Objects.requireNonNull(qualifications, "qualifications 不得为 null");
    }

    /** 发布结果：bundleDigest + revision；replayed = 同 digest 幂等重放（未新增行） */
    public record PublishResult(Digest bundleDigest, long revision, boolean replayed) {
    }

    /** 激活/回滚结果：moved=false = 幂等重放（本就是当前）或 CAS 竞争败者（409 面） */
    public record ActivationResult(Digest activeDigest, long revision, boolean moved) {
    }

    /** 发布：canonical digest 唯一 = 内容级幂等锚；同内容重发返回既有行（revision 不烧号）。
     *  携带 release_manifest 段的内容先过依赖闭包校验（P04），首发即拒缺失引用 */
    public PublishResult publish(Map<String, Object> content, String createdBy) {
        ConfigBundle candidate = ConfigBundle.of(content, createdBy, Instant.now());
        Optional<ConfigBundle> existing = repository.findByDigest(candidate.bundleDigest());
        if (existing.isPresent()) {
            ConfigBundle found = existing.get();
            log.info("bundle 幂等重放: {} revision={} by={}",
                    found.bundleDigest(), found.revision(), createdBy);
            return new PublishResult(found.bundleDigest(), found.revision(), true);
        }
        candidate.releaseManifest().ifPresent(manifest -> {
            requireClosure(manifest);
            log.info("release_manifest 依赖闭包通过: roles={} skills={} toolSchemas={}",
                    manifest.roles().keySet(), manifest.skills().size(),
                    manifest.toolSchemas().size());
        });
        ConfigBundle bundle = withRevision(candidate, repository.nextRevision());
        if (!repository.insert(bundle)) {
            // 并发同内容发布竞败：唯一约束拒收 → 落回既有行（幂等重放面）
            ConfigBundle winner = repository.findByDigest(bundle.bundleDigest()).orElseThrow();
            return new PublishResult(winner.bundleDigest(), winner.revision(), true);
        }
        log.info("bundle 发布: {} revision={} by={} policy={}", bundle.bundleDigest(),
                bundle.revision(), createdBy, bundle.policyVersion());
        return new PublishResult(bundle.bundleDigest(), bundle.revision(), false);
    }

    /** 资产注册结果：assetDigest + replayed = 同 (kind,digest) 幂等重放（未新增行） */
    public record AssetPublishResult(String kind, Digest assetDigest, boolean replayed) {
    }

    /** EN-01 资产注册：内容寻址（同内容重发幂等）；形状/密钥校验在域构造期 fail-closed */
    public AssetPublishResult registerAsset(String kind, Map<String, Object> content,
            String createdBy) {
        ReleaseAsset candidate = ReleaseAsset.of(kind, content, createdBy, Instant.now());
        boolean inserted = assets.insert(candidate);
        if (inserted) {
            log.info("资产注册: {} {} by={}", kind, candidate.assetDigest(), createdBy);
        } else {
            log.info("资产注册幂等重放: {} {} by={}", kind, candidate.assetDigest(), createdBy);
        }
        return new AssetPublishResult(kind, candidate.assetDigest(), !inserted);
    }

    /**
     * 激活（EN-02 资格门）：目标必须已发布且有未撤销 PASS 资格；expectedRevision =
     * 客户端预期的当前激活 revision（0 = 未激活约定）；重复激活当前 digest = 幂等
     * 重放（资格门先于重放判定不适用——已在位指针不被追溯降级，§九撤权语义）。
     */
    public ActivationResult activate(Digest digest, long expectedActiveRevision, String by) {
        Objects.requireNonNull(digest, "digest 不得为 null");
        ConfigBundle target = repository.findByDigest(digest)
                .orElseThrow(() -> new IllegalArgumentException("未知 bundle digest: " + digest));
        if (repository.activeDigest().map(target.bundleDigest()::equals).orElse(false)) {
            return new ActivationResult(target.bundleDigest(), target.revision(), false);
        }
        requireQualification(target.bundleDigest());
        return movePointer(target, expectedActiveRevision, by, "ACTIVATE");
    }

    /** 回滚（EN-02）：pointer 指回 toDigest（必须已发布且<b>同样过资格门</b>——
     *  P11：不能因"回滚"绕过有效性）；历史 bundle 行零改写（INV-AM5-5） */
    public ActivationResult rollback(Digest toDigest, long expectedActiveRevision, String by) {
        Objects.requireNonNull(toDigest, "toDigest 不得为 null");
        ConfigBundle target = repository.findByDigest(toDigest)
                .orElseThrow(() -> new IllegalArgumentException("未知 bundle digest: " + toDigest));
        if (repository.activeDigest().map(target.bundleDigest()::equals).orElse(false)) {
            return new ActivationResult(target.bundleDigest(), target.revision(), false);
        }
        requireQualification(target.bundleDigest());
        return movePointer(target, expectedActiveRevision, by, "ROLLBACK");
    }

    // ------------------------------------------------------- EN-02 发布资格面

    /**
     * 授予发布资格（评测身份写面；HTTP 授予端点归 EN-09/EN-10，本卡只立服务契约）。
     * 候选（与可选基线）必须已发布；FAIL/INCONCLUSIVE 照记（评测结论如实入账，
     * S09——门按 verdict 判定，记录在案不妨碍拒绝）。
     */
    public UUID grantQualification(Digest candidate, Digest baseline,
            String datasetManifestDigest, String runnerVersion, String graderVersion,
            String qualityVerdict, String usageStatus, String grantedScope, String grantedBy) {
        Objects.requireNonNull(candidate, "candidate 不得为 null");
        if (repository.findByDigest(candidate).isEmpty()) {
            throw new IllegalArgumentException("候选未发布，资格授予拒绝: " + candidate);
        }
        if (baseline != null && repository.findByDigest(baseline).isEmpty()) {
            throw new IllegalArgumentException("基线未发布，资格授予拒绝: " + baseline);
        }
        ReleaseQualification proof = new ReleaseQualification(UUID.randomUUID(), candidate,
                baseline, datasetManifestDigest, runnerVersion, graderVersion,
                qualityVerdict, usageStatus, grantedScope, grantedBy, Instant.now(),
                null, null, null);
        qualifications.insert(proof);
        log.info("资格授予: candidate={} verdict={} usage={} by={}", candidate,
                qualityVerdict, usageStatus, grantedBy);
        return proof.id();
    }

    /** 撤销资格：仅写撤销三列（P07——撤销后激活在事务内被拒，不追溯降级在位指针） */
    public boolean revokeQualification(UUID id, String by, String reason) {
        boolean revoked = qualifications.revoke(id, by, reason, Instant.now());
        if (revoked) {
            log.warn("资格撤销: id={} by={} reason={}", id, by, reason);
        }
        return revoked;
    }

    /**
     * 资格门（fail-fast 预检；事务内权威重验在仓储 activateQualified）。原因码前缀：
     * QUALIFICATION_ABSENT（无未撤销证明）/ QUALITY_NOT_PASS（最新证明非 PASS）。
     */
    private void requireQualification(Digest candidate) {
        Optional<ReleaseQualification> proof = qualifications.findUnrevokedFor(candidate);
        if (proof.isEmpty()) {
            throw new IllegalStateException(
                    "QUALIFICATION_ABSENT: 无未撤销评测证明，激活/回滚拒绝（候选 " + candidate + "）");
        }
        if (!proof.get().passQualified()) {
            throw new IllegalStateException("QUALITY_NOT_PASS(" + proof.get().qualityVerdict()
                    + "): 费用对账状态不参与门判定（MATCHED 不能代替质量 PASS，E05/S09）");
        }
    }

    /** GET /active 响应面：未激活 → 空 */
    public Optional<ConfigBundleRepository.ActivePointer> activePointer() {
        return repository.findActivePointer();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * EN-01 依赖闭包（P04）：manifest 段引用的每只资产必须已注册——角色 Prompt、
     * Skill 允许集、工具 schema 集逐只解析；缺失即 IAE（发布中止、零落库，指针不动）。
     */
    private void requireClosure(ReleaseManifest manifest) {
        List<String> missing = new ArrayList<>();
        manifest.roles().values()
                .forEach(d -> requireAsset(ReleaseAsset.KIND_PROMPT, d, missing));
        manifest.skills().forEach(d -> requireAsset(ReleaseAsset.KIND_SKILL, d, missing));
        manifest.toolSchemas()
                .forEach(d -> requireAsset(ReleaseAsset.KIND_TOOL_SCHEMA, d, missing));
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("依赖闭包校验失败（P04），缺失资产: " + missing);
        }
    }

    private void requireAsset(String kind, String digestHex, List<String> missing) {
        if (assets.findByDigest(kind, new Digest(digestHex)).isEmpty()) {
            missing.add(kind + ":" + digestHex);
        }
    }

    /** 发布形态（of()，revision 恒 1）→ 落库形态（仓储分配全局递增 revision） */
    private static ConfigBundle withRevision(ConfigBundle candidate, long revision) {
        return new ConfigBundle(candidate.id(), candidate.bundleDigest(), revision,
                candidate.content(), candidate.createdBy(), candidate.createdAt());
    }

    /**
     * EX-B1：指针移动与变更事实同事务——幂等重放早退（零事件）、CAS 败者事务内零插入
     * （评审 B1 两裁定）；仅 ROLLBACK 携 rollback_of（回滚前生效 digest）。
     * EN-02：CAS 锚从 expected digest 改为客户端 expected active revision（P06）。
     */
    private ActivationResult movePointer(ConfigBundle target, long expectedActiveRevision,
            String by, String action) {
        ConfigBundleRepository.ActivationFact fact = new ConfigBundleRepository.ActivationFact(
                action, CHANGE_SERVICE, CHANGE_ENVIRONMENT,
                "ROLLBACK".equals(action) ? repository.activeDigest().orElse(null) : null);
        if (!repository.activateQualified(target.bundleDigest(), expectedActiveRevision,
                by, Instant.now(), fact)) {
            // 并发竞争败者：expected revision 已漂移，调用方以 409 携最新投影回显
            log.warn("{} CAS 竞争失败: target={} expected={} by={}", action,
                    target.bundleDigest(), expectedActiveRevision, by);
            return new ActivationResult(repository.activeDigest().orElse(null),
                    repository.findActivePointer()
                            .map(ConfigBundleRepository.ActivePointer::revision).orElse(0L),
                    false);
        }
        log.info("{} 落位: {} revision={} by={}", action, target.bundleDigest(),
                target.revision(), by);
        return new ActivationResult(target.bundleDigest(), target.revision(), true);
    }
}
