package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.control.release.application.ConfigBundleService.ActivationResult;
import com.objwww.pr.control.release.application.ConfigBundleService.PublishResult;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * M5-09 ConfigBundleService 编排 UT：发布幂等（digest 唯一 = 内容级幂等锚）、
 * 激活单事务原子（pointer CAS 无半激活态）、回滚 = pointer 指回且不改历史行
 * （INV-AM5-5）、未激活态首激活 CAS（expectedCurrent = null）。
 */
class ConfigBundleServiceTest {

    private final InMemoryBundles repository = new InMemoryBundles();
    private final ConfigBundleService service = new ConfigBundleService(repository);

    /** 测试内存认账面：记录 CAS 调用与行改写面（历史行零改写可断言） */
    static final class InMemoryBundles implements ConfigBundleRepository {
        final List<ConfigBundle> rows = new ArrayList<>();
        final List<String> casCalls = new ArrayList<>();
        long revisionSeq = 0;
        Digest active;
        Instant activatedAt;
        String activatedBy;

        @Override
        public long nextRevision() {
            return ++revisionSeq;
        }

        @Override
        public boolean insert(ConfigBundle bundle) {
            if (findByDigest(bundle.bundleDigest()).isPresent()) {
                return false;
            }
            rows.add(bundle);
            return true;
        }

        @Override
        public Optional<ConfigBundle> findByDigest(Digest digest) {
            return rows.stream().filter(b -> b.bundleDigest().equals(digest)).findFirst();
        }

        @Override
        public Optional<Digest> activeDigest() {
            return Optional.ofNullable(active);
        }

        @Override
        public Optional<ConfigBundleRepository.ActivePointer> findActivePointer() {
            return active == null ? Optional.empty()
                    : Optional.of(new ConfigBundleRepository.ActivePointer(
                            active, findByDigest(active).orElseThrow().revision(), activatedAt));
        }

        @Override
        public boolean activate(Digest toDigest, Digest expectedCurrent, String by, Instant at) {
            casCalls.add((expectedCurrent == null ? "null" : expectedCurrent.hex())
                    + "->" + toDigest.hex());
            if ((active == null && expectedCurrent != null)
                    || (active != null && !active.equals(expectedCurrent))) {
                return false;
            }
            active = toDigest;
            activatedAt = at;
            activatedBy = by;
            return true;
        }
    }

    private static Map<String, Object> content(String promptVersion) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", promptVersion);
        return content;
    }

    // ---------------------------------------------------------------- 用例面

    @Test
    @DisplayName("发布：digest 按内容派生、revision 递增；同内容重发 = 幂等重放（同 digest 同 revision）")
    void publishIsIdempotentByDigest() {
        PublishResult first = service.publish(content("v7"), "release-operator");
        PublishResult replay = service.publish(content("v7"), "release-operator");
        PublishResult second = service.publish(content("v8"), "release-operator");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bundleDigest()).isEqualTo(first.bundleDigest());
        assertThat(replay.revision()).isEqualTo(first.revision());
        assertThat(second.bundleDigest()).isNotEqualTo(first.bundleDigest());
        assertThat(second.revision()).isEqualTo(first.revision() + 1);
        assertThat(repository.rows).hasSize(2);
    }

    @Test
    @DisplayName("激活：未激活态首激活（expectedCurrent=null CAS）→ 指针落位；重复激活同 digest = 幂等重放")
    void activateMovesPointerAtomically() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();

        ActivationResult first = service.activate(d1, "release-operator");
        assertThat(first.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
        assertThat(repository.activatedBy).isEqualTo("release-operator");
        assertThat(repository.casCalls).containsExactly("null->" + d1.hex());

        ActivationResult replay = service.activate(d1, "release-operator");
        assertThat(replay.moved()).isFalse();
        assertThat(replay.activeDigest()).isEqualTo(d1);
        assertThat(repository.casCalls).hasSize(1);

        ActivationResult second = service.activate(d2, "release-operator");
        assertThat(second.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d2);
    }

    @Test
    @DisplayName("激活冲突：CAS expected 不匹配（并发竞争败者）→ moved=false 且指针不动")
    void activateCasConflictLeavesPointerUntouched() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();
        service.activate(d1, "op");

        Digest staleExpected = null;
        boolean moved = repository.activate(d2, staleExpected, "intruder", Instant.now());
        assertThat(moved).isFalse();
        assertThat(repository.active).isEqualTo(d1);
    }

    @Test
    @DisplayName("激活未知 digest → IAE（先验证目标存在，不盲移指针）")
    void activateUnknownDigestRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.activate(Digest.sha256Of("ghost"), "op"));
    }

    @Test
    @DisplayName("回滚：pointer 指回旧 digest 且历史行零改写（行集合与 digest 集不变）")
    void rollbackRewindsPointerWithoutTouchingHistory() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        Digest d2 = service.publish(content("v8"), "op").bundleDigest();
        service.activate(d1, "op");
        service.activate(d2, "op");
        List<Digest> historyBefore = repository.rows.stream()
                .map(ConfigBundle::bundleDigest).toList();

        ActivationResult rollback = service.rollback(d1, "release-operator");

        assertThat(rollback.moved()).isTrue();
        assertThat(repository.active).isEqualTo(d1);
        assertThat(repository.rows).hasSize(2);
        assertThat(repository.rows.stream().map(ConfigBundle::bundleDigest).toList())
                .containsExactlyElementsOf(historyBefore);
    }

    @Test
    @DisplayName("回滚到当前激活 digest = 幂等重放（零 CAS 调用）；回滚未知 digest → IAE")
    void rollbackReplayAndUnknownTarget() {
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        service.activate(d1, "op");

        ActivationResult replay = service.rollback(d1, "op");
        assertThat(replay.moved()).isFalse();
        assertThat(repository.casCalls).hasSize(1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.rollback(Digest.sha256Of("ghost"), "op"));
    }

    @Test
    @DisplayName("active 视图：未激活 → 空；激活后返回 digest/revision/activatedAt 三件")
    void activePointerView() {
        assertThat(service.activePointer()).isEmpty();
        Digest d1 = service.publish(content("v7"), "op").bundleDigest();
        service.activate(d1, "op");
        Optional<ConfigBundleRepository.ActivePointer> view = service.activePointer();
        assertThat(view).isPresent();
        assertThat(view.get().bundleDigest()).isEqualTo(d1);
        assertThat(view.get().revision()).isEqualTo(1L);
        assertThat(view.get().activatedAt()).isNotNull();
    }
}
