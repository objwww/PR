package com.objwww.pr.notify.domain.service;

import com.objwww.pr.notify.domain.model.ClaimedNotification;

/**
 * 通知发送最小面（领取循环依赖；实现 = {@link FencedNotifyExecutor}，测试可假件替换）。
 * 独立顶层接口——嵌套在实现类内部会构成循环继承（javac 拒绝）。
 */
public interface NotifySender {

    FencedNotifyExecutor.Outcome execute(ClaimedNotification notification);
}
