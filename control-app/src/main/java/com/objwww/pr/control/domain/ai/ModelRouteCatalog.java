package com.objwww.pr.control.domain.ai;

import java.util.Optional;

/**
 * 当前配置路由目录（§4.7/I30）：checkpoint resume 按"保存的模型身份"查路由的落点。
 *
 * <p>实现者：ModelGateway（持有主备路由与契约身份派生规则）。
 * 消费者：ReviewStepExecutor → CheckpointResumeService 回调。
 */
public interface ModelRouteCatalog {

    /**
     * 按请求模型名查当前配置路由的契约身份。
     *
     * @param requestedModel checkpoint 保存身份三段中的 requestedModel 段
     * @return 当前配置中存在使用该模型的路由 → 其契约身份；不存在（路由已被移除）→ 空
     */
    Optional<ModelRouteIdentity> findContractIdentityByModel(String requestedModel);
}
