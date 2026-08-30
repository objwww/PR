package com.objwww.pr.control.domain.ai;

/** 单次调用 token 预算被突破（请求侧申请超额，或实际 completion 用量超预算）→ Step FAILED（EX-06） */
public final class ModelBudgetExceededException extends RuntimeException {

    public ModelBudgetExceededException(String message) {
        super(message);
    }
}
