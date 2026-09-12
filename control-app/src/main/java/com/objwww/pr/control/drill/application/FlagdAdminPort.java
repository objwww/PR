package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.FlagdState;

/**
 * DR-05 flagd 管理面窄口（drill 域视角；方案 §7.5 DR-05 卡「FlagAdminClient/服务端
 * 落点」的 drill 侧抽象）：读实际当前值与代际令牌 + 写 defaultVariant 单键
 * （BA-19 纪律）。eval 侧 {@code FlagdScenarioDriver.FlagAdminClient} 经
 * {@code asAdminPort()} 适配本口，driver 条件恢复与 sweeper 截止清扫共用同一传输面。
 */
public interface FlagdAdminPort {

    /** 读目标 flag 的实际当前值与代际令牌（条件恢复判定面；传输错误即抛，调用方三态化） */
    FlagdState read(String flag);

    /** 写 defaultVariant（BA-19 单键语义），返回服务端回执的实际生效值 */
    String write(String flag, String variant);
}
