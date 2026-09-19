package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;

/**
 * DR-04 演练恢复/核验端口（worker 执行面；对称 {@link DrillInjectionPort} 的三态纪律，
 * §7.4「恢复超时/残留 = RECOVERY_FAILED 保留占位」）：
 * <ul>
 *   <li><b>recover</b>（RECOVERING 相位驱动）：{@link RecoverKind#RECOVERED} = 恢复动作
 *       已被管理面受理/确认（flagd 条件写回 RESTORED；chaos 会话 off 受理或已收口/
 *       不存在）→ RECOVERING→VERIFYING；{@link RecoverKind#FAILED} = 确定不可恢复
 *       （flagd 冲突不覆盖/配置缺陷）→ RECOVERY_FAILED 诚实卡因；
 *       {@link RecoverKind#UNKNOWN} = 结果无法判定（传输失败/CAS 竞争）→ 保持
 *       RECOVERING 下拍重试，恢复窗口上限归 worker 截止对账（不许死循环）；</li>
 *   <li><b>verify</b>（VERIFYING 相位驱动）：{@link VerifyKind#VERIFIED} = 症状清除
 *       （期望告警全部 resolved）→ CLOSED + outcome 落真值（outcome 推导归 worker）；
 *       {@link VerifyKind#PENDING} = 仍有残留 firing 或探针暂不可读 → 保持 VERIFYING
 *       下拍重试；{@link VerifyKind#FAILED} = 确定核验不可达（配置缺陷）→
 *       RECOVERY_FAILED；</li>
 *   <li>恢复方向不受 launch 能力位把守（launch=false 只关闭新注入发起，恢复/核验是
 *       收场方向必须永远可执行）。</li>
 * </ul>
 * 实现面：{@link CompositeDrillRecovery} = DR-04 接线（按模板 driver 分派
 * arena/flagd 适配器 + 共享告警面核验）；{@link NotImplemented} 保留为未接线装配面的
 * 如实失败——确定无法执行恢复，RECOVERY_FAILED 保留占位，不冒充现场干净。
 */
public interface DrillRecoveryPort {

    enum RecoverKind {RECOVERED, FAILED, UNKNOWN}

    record RecoverOutcome(RecoverKind kind, String reason) {

        public static RecoverOutcome recovered(String reason) {
            return new RecoverOutcome(RecoverKind.RECOVERED, reason);
        }

        public static RecoverOutcome failed(String reason) {
            return new RecoverOutcome(RecoverKind.FAILED, reason);
        }

        public static RecoverOutcome unknown(String reason) {
            return new RecoverOutcome(RecoverKind.UNKNOWN, reason);
        }
    }

    enum VerifyKind {VERIFIED, PENDING, FAILED}

    record VerifyOutcome(VerifyKind kind, String reason) {

        public static VerifyOutcome verified(String reason) {
            return new VerifyOutcome(VerifyKind.VERIFIED, reason);
        }

        public static VerifyOutcome pending(String reason) {
            return new VerifyOutcome(VerifyKind.PENDING, reason);
        }

        public static VerifyOutcome failed(String reason) {
            return new VerifyOutcome(VerifyKind.FAILED, reason);
        }
    }

    RecoverOutcome recover(DrillJob job);

    VerifyOutcome verify(DrillJob job);

    /** 未接线装配面的默认实现：确定无法恢复（FAILED 如实卡因，不留死循环重试） */
    final class NotImplemented implements DrillRecoveryPort {

        public static final String REASON =
                "RECOVERY_NOT_IMPLEMENTED: 恢复/核验执行接线未交付——确定无法执行恢复，"
                        + "作业按 RECOVERY_FAILED 保留占位待人工核验，不冒充现场干净";

        @Override
        public RecoverOutcome recover(DrillJob job) {
            return RecoverOutcome.failed(REASON);
        }

        @Override
        public VerifyOutcome verify(DrillJob job) {
            return VerifyOutcome.failed(REASON);
        }
    }
}
