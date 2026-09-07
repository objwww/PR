package com.objwww.pr.control.ops.domain.statemachine;

import com.objwww.pr.control.ops.domain.model.CaseAction;
import com.objwww.pr.control.ops.domain.model.CaseStatus;

import java.util.Objects;

/**
 * 非法 (状态, 动作) 组合（M5-11 状态机穷举拒绝面；调用方转 409/422 语义在 M5-12 API）。
 * currentStatus 允许 null（CREATE 只认 null 态；null 态 × 非 CREATE 亦非法）。
 */
public class IllegalCaseActionException extends IllegalStateException {

    private final CaseStatus currentStatus;
    private final CaseAction action;

    public IllegalCaseActionException(CaseStatus currentStatus, CaseAction action) {
        super("非法 Case 迁移: status=" + currentStatus + " × action=" + action);
        this.currentStatus = currentStatus;
        this.action = Objects.requireNonNull(action, "action");
    }

    public CaseStatus currentStatus() {
        return currentStatus;
    }

    public CaseAction action() {
        return action;
    }
}
