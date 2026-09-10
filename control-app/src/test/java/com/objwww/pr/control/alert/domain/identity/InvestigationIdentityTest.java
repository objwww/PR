package com.objwww.pr.control.alert.domain.identity;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-A0 三 digest 身份族单测（F04/F14/F23）：①三类型各自校验 64 位小写 hex，非
 * hex 构造期拒绝；②三类型互不转换——混用在编译期被拒（本测试以正确类型组合通过
 * 编译为面，错误组合无法通过编译，见 NativeRcaAgent 两参分型）；③调查输入 digest
 * 稳定性（同输入同 digest，任一材料变化必变）；④冻结窗口政策（铸造时刻-600s）。
 */
class InvestigationIdentityTest {

    private static final String HEX_A = "ab".repeat(32);
    private static final String HEX_B = "cd".repeat(32);
    private static final Instant MINTED = Instant.parse("2026-09-10T08:00:00Z");

    // ---------------------------------------------------------- ① 类型守卫

    @Test
    void threeDigestKindsRejectNonHexValues() {
        assertThatThrownBy(() -> new ConfigDigest("bundle-v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationInputDigest("snap-abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceSnapshotDigest("ee".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigDigest("AB".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class); // 大写拒绝
    }

    @Test
    void threeDigestKindsCarryDistinctRoles() {
        // 各司其职：同一 hex 值在三个类型下是三个互不相同的身份对象（无隐式互转）
        ConfigDigest config = new ConfigDigest(HEX_A);
        InvestigationInputDigest input = new InvestigationInputDigest(HEX_A);
        EvidenceSnapshotDigest snapshot = new EvidenceSnapshotDigest(HEX_A);
        assertThat(config.hex()).isEqualTo(input.hex()).isEqualTo(snapshot.hex());
        // 三者无 isEqualsTo 关系可断言——类型不同即编译期隔离；此行能编译即契约面
    }

    // ---------------------------------------------------------- ③ 输入 digest 稳定性

    @Test
    void sameInputsSameInvestigationDigest() {
        UUID incidentId = UUID.randomUUID();
        InvestigationInputs a = inputs(incidentId, HEX_A, MINTED);
        InvestigationInputs b = inputs(incidentId, HEX_A, MINTED);
        assertThat(a.inputDigest()).isEqualTo(b.inputDigest());
    }

    @Test
    void anyMaterialChangeChangesInvestigationDigest() {
        InvestigationInputs base = inputs(HEX_A, MINTED);
        InvestigationInputs otherIncident = inputs(HEX_B, MINTED);
        InvestigationInputs otherWindow = inputs(HEX_A, MINTED.plusSeconds(1));
        assertThat(base.inputDigest()).isNotEqualTo(otherIncident.inputDigest());
        assertThat(base.inputDigest()).isNotEqualTo(otherWindow.inputDigest());
    }

    @Test
    void investigationDigestIsHex64() {
        assertThat(inputs(HEX_A, MINTED).inputDigest().hex()).matches("[0-9a-f]{64}");
    }

    // ---------------------------------------------------------- ④ 冻结窗口政策

    @Test
    void freezeAtAnchorsWindowAtMintingTime() {
        InvestigationInputs inputs = inputs(HEX_A, MINTED);
        assertThat(inputs.windowStart()).isEqualTo(MINTED.minusSeconds(600));
        assertThat(inputs.windowEnd()).isEqualTo(MINTED);
        assertThat(inputs.timeRange())
                .isEqualTo(MINTED.getEpochSecond() - 600 + "/" + MINTED.getEpochSecond());
    }

    @Test
    void inputsRejectInconsistentMaterial() {
        assertThatThrownBy(() -> new InvestigationInputs(UUID.randomUUID(), " ",
                0, MINTED, MINTED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationInputs(UUID.randomUUID(), "k", 0,
                MINTED, MINTED.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class); // start > end 拒绝
    }

    // ------------------------------------------------------------------ 夹具

    private static InvestigationInputs inputs(String incidentKeyHex, Instant mintedAt) {
        return inputs(UUID.randomUUID(), incidentKeyHex, mintedAt);
    }

    private static InvestigationInputs inputs(UUID incidentId, String incidentKeyHex,
            Instant mintedAt) {
        Incident incident = new Incident(incidentId,
                "alertname=HighErrorRate|service=checkout",
                IncidentStatus.FIRING, 3, mintedAt.minusSeconds(60), mintedAt.minusSeconds(60),
                null, null, null, 1, 1, 0, null,
                mintedAt.minusSeconds(60), mintedAt.minusSeconds(60),
                mintedAt.minusSeconds(60), mintedAt);
        return InvestigationInputs.freezeAt(incident, mintedAt);
    }
}
