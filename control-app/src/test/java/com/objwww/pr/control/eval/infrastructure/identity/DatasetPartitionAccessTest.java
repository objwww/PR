package com.objwww.pr.control.eval.infrastructure.identity;

import com.objwww.pr.control.eval.domain.model.PartitionClass;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalIdentityGuard 身份矩阵（M5-02）：权限矩阵的应用层单点——
 * Agent/RAG/调优界面身份对 HOLDOUT/REDTEAM/GT 恒 0（方案 §12.1 L2 原文的
 * UT 面）；SCORING 封存门前不见 HOLDOUT；两个 Gate 各自单分区。
 * DB 面等价物（RLS + 四分区角色）由 PostgresDatasetPartitionAccessTest IT 覆盖。
 */
class DatasetPartitionAccessTest {

    private final EvalIdentityGuard guard = new EvalIdentityGuard();

    @Test
    void agentRagAndTuningUiNeverSeeHoldoutOrRedteamAndNeverReadGroundTruth() {
        for (EvalIdentityGuard.EvalIdentity identity : Set.of(
                EvalIdentityGuard.EvalIdentity.AGENT,
                EvalIdentityGuard.EvalIdentity.RAG,
                EvalIdentityGuard.EvalIdentity.TUNING_UI)) {
            assertThat(guard.visiblePartitions(identity))
                    .as("%s 可见面 = TUNING+VALIDATION", identity)
                    .containsExactlyInAnyOrder(PartitionClass.TUNING, PartitionClass.VALIDATION);
            assertThatIllegalStateException()
                    .as("%s 对 HOLDOUT 查询恒 0", identity)
                    .isThrownBy(() -> guard.assertQueryAllowed(identity, PartitionClass.HOLDOUT))
                    .withMessageContaining("HOLDOUT");
            assertThatIllegalStateException()
                    .as("%s 对 REDTEAM 查询恒 0", identity)
                    .isThrownBy(() -> guard.assertQueryAllowed(identity, PartitionClass.REDTEAM));
            assertThat(guard.isGroundTruthReadable(identity))
                    .as("%s 对 GT 查询恒 0（延迟授权沿用）", identity).isFalse();
        }
    }

    @Test
    void scoringSeesTuningValidationRedteamButHoldoutStaysSealed() {
        var scoring = EvalIdentityGuard.EvalIdentity.SCORING;
        assertThat(guard.visiblePartitions(scoring)).containsExactlyInAnyOrder(
                PartitionClass.TUNING, PartitionClass.VALIDATION, PartitionClass.REDTEAM);
        assertThatCode(() -> guard.assertQueryAllowed(scoring, PartitionClass.TUNING))
                .doesNotThrowAnyException();
        assertThatIllegalStateException()
                .as("SCORING 封存门前不可见 HOLDOUT（V21 RLS eval_app 策略同谓词）")
                .isThrownBy(() -> guard.assertQueryAllowed(scoring, PartitionClass.HOLDOUT));
        assertThat(guard.isGroundTruthReadable(scoring))
                .as("评分身份 = V3 GT 延迟授权的读取方").isTrue();
    }

    @Test
    void gatesAreSinglePartitionAndLeastPrivilege() {
        assertThat(guard.visiblePartitions(EvalIdentityGuard.EvalIdentity.HOLDOUT_GATE))
                .containsExactly(PartitionClass.HOLDOUT);
        assertThatCode(() -> guard.assertQueryAllowed(
                EvalIdentityGuard.EvalIdentity.HOLDOUT_GATE, PartitionClass.HOLDOUT))
                .doesNotThrowAnyException();
        assertThatIllegalStateException()
                .isThrownBy(() -> guard.assertQueryAllowed(
                        EvalIdentityGuard.EvalIdentity.HOLDOUT_GATE, PartitionClass.TUNING));

        assertThat(guard.visiblePartitions(EvalIdentityGuard.EvalIdentity.REDTEAM_GATE))
                .containsExactly(PartitionClass.REDTEAM);
        assertThatIllegalStateException()
                .isThrownBy(() -> guard.assertQueryAllowed(
                        EvalIdentityGuard.EvalIdentity.REDTEAM_GATE, PartitionClass.VALIDATION));
        assertThat(guard.isGroundTruthReadable(EvalIdentityGuard.EvalIdentity.HOLDOUT_GATE))
                .as("封存门为评分专属的 GT 读取面").isTrue();
        assertThat(guard.isGroundTruthReadable(EvalIdentityGuard.EvalIdentity.REDTEAM_GATE))
                .isFalse();
    }

    @Test
    void visiblePartitionsViewIsDefensivelyCopied() {
        Set<PartitionClass> view = guard.visiblePartitions(EvalIdentityGuard.EvalIdentity.AGENT);
        assertThatThrownBy(() -> view.add(PartitionClass.HOLDOUT))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
