package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.ResolvedResource;

import java.util.Optional;

/**
 * 权威资源解析端口（PB-B2，设计基线 §2.2 R6）：请求键 → canonical 身份。
 * 信任根论证——HMAC 只证明来源发过这条数据，不证明 env/resource 标签是正确的
 * 授权信息；本端口是授权面<b>唯一</b>合法身份来源（B 组不变量：告警标签零授权
 * 效力）。miss = fail-closed，请求键不可授权。
 */
public interface ResourceResolver {

    /** miss = Optional.empty（fail-closed；调用方必须显式走拒绝/失败事件路径） */
    Optional<ResolvedResource> resolve(String requestedKey);
}
