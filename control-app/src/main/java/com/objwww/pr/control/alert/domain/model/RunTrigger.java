package com.objwww.pr.control.alert.domain.model;

/**
 * run 铸造来源：INITIAL=episode 首轮；RERUN=材料变化（investigation_hash 变化）触发的下一轮（§6.7）。
 */
public enum RunTrigger {
    INITIAL, RERUN
}
