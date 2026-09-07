package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ConfigBundle 应用服务（M5-09）：Git 编辑源 → 发布（canonical digest 幂等落库）→
 * <b>单事务原子激活</b>（事务边界在 PostgresConfigBundleRepository 的 pointer CAS，
 * 无半激活态）→ 回滚 = pointer 指回旧 digest，不改历史行（INV-AM5-5）。
 * 决策可回溯：每次激活/回滚记录 bundle digest/revision/操作者（审计日志行 +
 * config_bundle_active.activated_by 落档）。
 *
 * <p>沿 GoldenCandidateService 惯例：本类不加 @Service（装配归 PersistenceConfig，
 * docker profile 唯一装配点）；未装配 Postgres 仓储 bean 前测试直构。
 */
public class ConfigBundleService {

    private static final Logger log = LoggerFactory.getLogger(ConfigBundleService.class);

    private final ConfigBundleRepository repository;

    public ConfigBundleService(ConfigBundleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不得为 null");
    }

    /** 发布结果：bundleDigest + revision；replayed = 同 digest 幂等重放（未新增行） */
    public record PublishResult(Digest bundleDigest, long revision, boolean replayed) {
    }

    /** 激活/回滚结果：moved=false = 幂等重放（本就是当前）或 CAS 竞争败者（409 面） */
    public record ActivationResult(Digest activeDigest, long revision, boolean moved) {
    }

    /** 发布：canonical digest 唯一 = 内容级幂等锚；同内容重发返回既有行（revision 不烧号） */
    public PublishResult publish(Map<String, Object> content, String createdBy) {
        ConfigBundle candidate = ConfigBundle.of(content, createdBy, Instant.now());
        Optional<ConfigBundle> existing = repository.findByDigest(candidate.bundleDigest());
        if (existing.isPresent()) {
            ConfigBundle found = existing.get();
            log.info("bundle 幂等重放: {} revision={} by={}",
                    found.bundleDigest(), found.revision(), createdBy);
            return new PublishResult(found.bundleDigest(), found.revision(), true);
        }
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

    /** 激活：目标必须已发布；重复激活当前 digest = 幂等重放；CAS 败者 moved=false */
    public ActivationResult activate(Digest digest, String by) {
        Objects.requireNonNull(digest, "digest 不得为 null");
        ConfigBundle target = repository.findByDigest(digest)
                .orElseThrow(() -> new IllegalArgumentException("未知 bundle digest: " + digest));
        return movePointer(target, by, "activate");
    }

    /** 回滚：pointer 指回 toDigest（必须已发布）；历史 bundle 行零改写（INV-AM5-5） */
    public ActivationResult rollback(Digest toDigest, String by) {
        Objects.requireNonNull(toDigest, "toDigest 不得为 null");
        ConfigBundle target = repository.findByDigest(toDigest)
                .orElseThrow(() -> new IllegalArgumentException("未知 bundle digest: " + toDigest));
        return movePointer(target, by, "rollback");
    }

    /** GET /active 响应面：未激活 → 空 */
    public Optional<ConfigBundleRepository.ActivePointer> activePointer() {
        return repository.findActivePointer();
    }

    // ------------------------------------------------------------------ 内部

    /** 发布形态（of()，revision 恒 1）→ 落库形态（仓储分配全局递增 revision） */
    private static ConfigBundle withRevision(ConfigBundle candidate, long revision) {
        return new ConfigBundle(candidate.id(), candidate.bundleDigest(), revision,
                candidate.content(), candidate.createdBy(), candidate.createdAt());
    }

    private ActivationResult movePointer(ConfigBundle target, String by, String action) {
        Digest current = repository.activeDigest().orElse(null);
        if (current != null && current.equals(target.bundleDigest())) {
            return new ActivationResult(current, target.revision(), false);
        }
        if (!repository.activate(target.bundleDigest(), current, by, Instant.now())) {
            // 并发竞争败者：expected 已漂移，调用方以 409 携最新投影回显
            log.warn("{} CAS 竞争失败: target={} by={}", action, target.bundleDigest(), by);
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
