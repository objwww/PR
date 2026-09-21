package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 系统运行时旗标端口（JE-01，V159 alert_runtime_flag）：页面可写的系统级布尔
 * 开关存储（首行 jev_enhanced = Jev 增强路径开关）。与铸造冻结面解耦——本表是
 * "当前意愿"，run 行 jev_enabled 列才是"该次调查的事实"；执行面只读后者。
 * upsert 每方法自含短事务。
 */
public interface RuntimeFlagRepository {

    /** 旗标当前值；无行 = empty（解析方回退配置默认） */
    Optional<Boolean> findEnabled(String name);

    /** 幂等写（ON CONFLICT 同名覆盖）；updatedBy/reason 供审计面 */
    void upsert(String name, boolean enabled, String updatedBy, String reason,
            Instant at);

    /** 旗标行读视图（透出"谁在何时为何切换"） */
    record FlagRow(String name, boolean enabled, String updatedBy, String reason,
                   Instant updatedAt) {
    }

    /** 行级读（含审计面；无行 = empty） */
    Optional<FlagRow> findByName(String name);
}
