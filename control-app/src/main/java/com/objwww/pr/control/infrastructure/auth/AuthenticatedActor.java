package com.objwww.pr.control.infrastructure.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

/**
 * 当前认证主体（EX-C3a）：actor 唯一来源——浏览器会话用户名或机器线主体
 * （machine:&lt;线&gt;）。O-4 过渡面（客户端自报 X-Operator-Id）随 6 控制器手抄
 * 验签一并摘除。
 */
public final class AuthenticatedActor {

    private AuthenticatedActor() {
    }

    /** 安全链保证已认证（匿名被入口点 401 拦截）；缺席即装配缺陷，快速失败。 */
    public static String name() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Objects.requireNonNull(authentication, "安全链未铸认证主体——authorize 规则或过滤器装配缺陷");
        return authentication.getName();
    }
}
