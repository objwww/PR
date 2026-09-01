package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.TypedOutcome;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;

import java.util.Map;

/**
 * 命令 ↔ GitHub 类型化请求的纯翻译器（评审修正 #4，I4/B27）：
 * 只产 TypedWriteRequest/TypedReadRequest 并解释响应，<b>不引用、不调用 GitHubWriteAdapter，
 * 不 import 任何 HTTP 客户端</b>（ArchUnit AFT-04 卡死）。
 *
 * <p>实现无状态：幂等探针匹配所需的身份（operation_id / marker）经参数传入，不驻留实例字段。
 */
public interface PublicationHandler {

    CommandType commandType();

    /** CONFIRMED 时登记 publication_resource 的类型 */
    PublicationResourceType resourceType();

    /** publication_resource.marker：远端幂等探针本体（external_id / 隐藏 marker） */
    String resourceMarker(ClaimedCommand command);

    /** 命令 + payload → 类型化写请求 */
    TypedWriteRequest buildRequest(ClaimedCommand command, Map<String, Object> payload);

    /** 写响应 → 统一归类（§6.3/EX-01/02 归类表） */
    TypedOutcome interpret(TypedResponse response);

    /** 命令 + payload → 类型化探测读请求（§6.3 RemoteIdentityStrategy） */
    TypedReadRequest buildProbe(ClaimedCommand command, Map<String, Object> payload);

    /** 探测响应 → reconcile 判定；列表型探针的 notFound 只代表本页未命中 */
    ProbeResult interpretProbe(TypedResponse response, ClaimedCommand command);

    /** 内容型资源的当前期望正文 digest；状态型返回 null。 */
    default com.objwww.pr.shared.Digest expectedContentDigest(
            ClaimedCommand command, Map<String, Object> payload) {
        return null;
    }

    /**
     * DriftReconciler 的 repo 级 sanity 读探针（M1-T08，方案 §4.6/F-3）：资源探针 404 时
     * 用以确认 token/权限/仓库可达——通过才允许标 MISSING。repo 级探针与命令类型无关，
     * 默认实现即可；触网仍只经 FencedPublicationExecutor（I4 不破）。
     */
    default TypedReadRequest buildSanityProbe(String repositoryFullName) {
        return new TypedReadRequest(GitHubOperation.GET_REPO, repositoryFullName, Map.of());
    }
}
