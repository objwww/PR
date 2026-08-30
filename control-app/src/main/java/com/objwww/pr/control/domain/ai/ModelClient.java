package com.objwww.pr.control.domain.ai;

/**
 * 领域层唯一模型依赖（§3 类设计）：Spring AI 仅作 infrastructure 适配器存在。
 * 接口不含任何供应商概念；超时/预算违约以领域异常表达，安全步骤不降级（§6.6）。
 */
public interface ModelClient {

    /** 单次补全。超预算抛 {@link ModelBudgetExceededException}，超时抛 {@link ModelTimeoutException} */
    ModelResult complete(ModelRequest request);
}
