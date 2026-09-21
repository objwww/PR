package com.objwww.pr.control.alert.application;

/**
 * Jev 增强开关（JE-01）：铸造点在铸 run 时读取的"当前意愿"——随行冻结进
 * rca_run.jev_enabled（V159），执行期只读；切换开关不改变在跑调查。
 * 生产实现 = {@link RuntimeJevRunFlag}（页面旗标 → 配置默认回退）。
 */
@FunctionalInterface
public interface JevRunFlag {

    /** 新铸 run 是否进入 Jev 增强路径 */
    boolean enabledForNewRuns();

    /** 恒关（测试/旧构造兼容默认 = 现有链路原样） */
    JevRunFlag OFF = () -> false;
}
