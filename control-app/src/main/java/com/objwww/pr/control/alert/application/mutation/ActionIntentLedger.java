package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.mutation.ActionIntent;

/**
 * R2/R3 意图台账端口（PB-B1，V114 action_intent）：ToolGateway VALIDATE_ONLY 路径
 * 的持久落账面——意图行与意图事件（TOOL_INTENT_VALIDATED）<b>同一短事务</b>提交，
 * 授权事实只来自 rca_event 读路径（B 组不变量），账本行是其投影输入。
 *
 * <p>事务语义：{@link #record} 用 REQUIRES_NEW（进度面语义同
 * {@link RcaEventAppender#appendIndependent}）——调查主事务回滚不影响已入账意图
 * （意图已发生即事实）；行内事件 append 以 REQUIRED 加入本短事务。
 */
public interface ActionIntentLedger {

    /**
     * 意图入账 + 意图事件同短事务；返回事件 per-run seq。
     * intent_id 由调用方生成并已含于 intent（事件 payload 携带同键）。
     */
    long record(ActionIntent intent, RcaEventAppender.EventDraft intentEvent);
}
